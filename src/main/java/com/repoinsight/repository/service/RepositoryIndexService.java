
package com.repoinsight.repository.service;

import com.repoinsight.repository.entity.RepositoryInfo;
import com.repoinsight.repository.repository.RepositoryInfoRepository;
import org.springframework.scheduling.annotation.Async;
import tools.jackson.databind.JsonNode;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;

@Service
public class RepositoryIndexService {

    private static final Logger log = LoggerFactory.getLogger(RepositoryIndexService.class);
    private static final int MAX_CHUNK_CHARS = 300;
    private static final int CHUNK_OVERLAP_CHARS = 50;
    private static final int MAX_QUICK_INDEX_CHUNKS = 24;
    private static final int MAX_DOCUMENTS_PER_BATCH = 8;
    private static final int MAX_CONCURRENT_DOWNLOADS = 8;
    private static final Set<String> EXCLUDED_PATH_SEGMENTS = Set.of(
            ".git",
            ".gradle",
            "node_modules",
            "build",
            "target",
            "dist",
            "out",
            "generated",
            "vendor",
            "vendors",
            "third_party",
            "third-party",
            "test",
            "tests",
            "testing",
            "examples",
            "samples",
            "benchmark",
            "benchmarks");

    private final RepositoryInfoRepository repositoryInfoRepository;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final RestClient githubClient;
    private final RestClient rawContentClient;

    public RepositoryIndexService(
            RepositoryInfoRepository repositoryInfoRepository,
            VectorStore vectorStore,
            JdbcTemplate jdbcTemplate) {
        this.repositoryInfoRepository = repositoryInfoRepository;
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.githubClient = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .build();
        SimpleClientHttpRequestFactory rawContentRequestFactory =
                new SimpleClientHttpRequestFactory();
        rawContentRequestFactory.setConnectTimeout(Duration.ofSeconds(10));
        rawContentRequestFactory.setReadTimeout(Duration.ofSeconds(30));
        this.rawContentClient = RestClient.builder()
                .requestFactory(rawContentRequestFactory)
                .build();
    }

    public Long findSmallestAvailableId() {
        List<RepositoryInfo> repositories =
                repositoryInfoRepository.findAll(
                        org.springframework.data.domain.Sort.by("id")
                );

        long expectedId = 1;

        for (RepositoryInfo repository : repositories) {
            long currentId = repository.getId();

            if (currentId == expectedId) {
                expectedId++;
            } else if (currentId > expectedId) {
                break;
            }
        }

        return expectedId;
    }

    public void prepareIndexing(Long repositoryId) {
        RepositoryInfo repository = repositoryInfoRepository
                .findById(repositoryId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Repository not found: "
                                + repositoryId));

        updateStatus(
                repository,
                "PENDING",
                0,
                0,
                0,
                "Queued for indexing",
                null);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void markInterruptedIndexingJobs() {
        for (RepositoryInfo repository : repositoryInfoRepository.findAll()) {
            String status = repository.getIndexingStatus();
            if ("IN_PROGRESS".equalsIgnoreCase(status)
                    || "PENDING".equalsIgnoreCase(status)) {
                updateStatus(
                        repository,
                        "FAILED",
                        repository.getIndexingProgress() == null
                                ? 0 : repository.getIndexingProgress(),
                        repository.getIndexedChunks(),
                        repository.getIndexingTotalChunks(),
                        "Interrupted",
                        "Indexing was interrupted when the backend stopped. Retry indexing.");
            }
        }
    }

    @Async("indexingExecutor")
    public CompletableFuture<Integer> indexRepositoryAsync(Long repositoryId) {
        try {
            int chunks = indexRepository(repositoryId);
            return CompletableFuture.completedFuture(chunks);
        } catch (RuntimeException indexingException) {
            try {
                repositoryInfoRepository.findById(repositoryId)
                        .ifPresent(repository -> updateStatus(
                                repository,
                                "FAILED",
                                repository.getIndexingProgress() == null
                                        ? 0 : repository.getIndexingProgress(),
                                repository.getIndexedChunks(),
                                repository.getIndexingTotalChunks(),
                                "Indexing failed",
                                indexingException.getMessage()));
            } catch (RuntimeException statusException) {
                indexingException.addSuppressed(statusException);
            }
            return CompletableFuture.failedFuture(indexingException);
        }
    }

    public int indexRepository(Long repositoryId) {
        long indexingStartedNanos = System.nanoTime();
        RepositoryInfo repository = repositoryInfoRepository
                .findById(repositoryId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Repository not found: "
                                + repositoryId));

        updateStatus(
                repository,
                "IN_PROGRESS",
                0,
                0,
                0,
                "Loading repository file tree",
                null);
        String[] parts = parseGitHubUrl(repository.getGithubUrl());
        String owner = parts[0];
        String repo = parts[1];
        String branch = repository.getDefaultBranch();

        if (branch == null || branch.isBlank()) {
            branch = "main";
        }

        String finalBranch = branch;

        JsonNode treeResponse = githubClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/git/trees/{branch}")
                        .queryParam("recursive", "1")
                        .build(owner, repo, finalBranch))
                .retrieve()
                .body(JsonNode.class);

        if (treeResponse == null || !treeResponse.has("tree")) {
            throw new IllegalStateException("Could not fetch repository tree");
        }

        List<JsonNode> indexableFiles = new ArrayList<>();

        for (JsonNode item : treeResponse.path("tree")) {
            if ("blob".equals(item.path("type").asText())
                    && isIndexable(
                            item.path("path").asText(),
                            item.path("size").asLong(0))) {
                indexableFiles.add(item);
            }
        }

        if (indexableFiles.isEmpty()) {
            throw new IllegalStateException(
                    "No indexable source files were found.");
        }

        List<SelectedIndexFile> selectedFiles =
                selectQuickIndexFiles(indexableFiles, MAX_QUICK_INDEX_CHUNKS);
        Map<String, ExistingFileIndex> existingFiles = loadExistingFileIndex(repositoryId);
        List<SelectedIndexFile> changedFiles = new ArrayList<>();
        Map<String, String> reusableFiles = new HashMap<>();
        long estimatedTotalChunks = 0;
        long reusableChunkCount = 0;
        for (SelectedIndexFile selectedFile : selectedFiles) {
            JsonNode item = selectedFile.file();
            String path = item.path("path").asText();
            String blobSha = item.path("sha").asText("");
            ExistingFileIndex existing = existingFiles.get(path);
            if (!blobSha.isBlank() && existing != null
                    && blobSha.equals(existing.blobSha())
                    && selectedFile.chunkLimit() == existing.chunkCount()) {
                reusableFiles.put(path, blobSha);
                reusableChunkCount += existing.chunkCount();
            } else {
                changedFiles.add(selectedFile);
                estimatedTotalChunks += selectedFile.chunkLimit();
            }
        }
        int statusTotalChunks = (int) Math.min(
                estimatedTotalChunks + reusableChunkCount, Integer.MAX_VALUE);
        String indexVersion = UUID.randomUUID().toString();
        int indexedDocuments = 0;
        int lastProgress = 5;
        boolean embeddingStageStarted = false;
        int downloadedFiles = 0;
        List<Document> documentBatch =
                new ArrayList<>(MAX_DOCUMENTS_PER_BATCH);
        updateStatus(
                repository,
                "IN_PROGRESS",
                5,
                0,
                statusTotalChunks,
                changedFiles.isEmpty()
                        ? "Reusing unchanged quick Q&A index"
                        : "Downloading source files (0/" + changedFiles.size() + ")",
                null);
        try {
            try (var downloadExecutor =
                         Executors.newVirtualThreadPerTaskExecutor()) {
                for (int offset = 0; offset < changedFiles.size();
                        offset += MAX_CONCURRENT_DOWNLOADS) {
                    int batchEnd = Math.min(
                            offset + MAX_CONCURRENT_DOWNLOADS,
                            changedFiles.size());
                    List<CompletableFuture<DownloadedFile>> downloads =
                            new ArrayList<>(batchEnd - offset);

                    for (int fileIndex = offset; fileIndex < batchEnd; fileIndex++) {
                        String path = changedFiles.get(fileIndex).file()
                                .path("path").asText();
                        downloads.add(CompletableFuture.supplyAsync(
                                () -> downloadFile(owner, repo, finalBranch, path),
                                downloadExecutor));
                    }

                    for (int fileIndex = offset; fileIndex < batchEnd; fileIndex++) {
                        DownloadedFile downloaded;
                        try {
                            downloaded = downloads.get(fileIndex - offset).join();
                        } catch (CompletionException exception) {
                            if (exception.getCause() instanceof RuntimeException cause) {
                                throw cause;
                            }
                            throw exception;
                        }
                        downloadedFiles++;
                        if (!embeddingStageStarted) {
                            int downloadProgress = 5 + (int) (
                                    (long) downloadedFiles * 5
                                            / Math.max(1, changedFiles.size()));
                            lastProgress = Math.max(lastProgress, downloadProgress);
                            updateStatus(
                                    repository,
                                    "IN_PROGRESS",
                                    lastProgress,
                                    indexedDocuments,
                                    statusTotalChunks,
                                    "Downloading source files (" + downloadedFiles
                                            + "/" + changedFiles.size() + ")",
                                    null);
                        }

                        if (downloaded.content() != null
                                && !downloaded.content().isBlank()) {
                            List<String> chunks = chunkContent(downloaded.content());
                            int chunkLimit = changedFiles.get(fileIndex).chunkLimit();
                            for (int chunkNumber = 0;
                                    chunkNumber < Math.min(chunks.size(), chunkLimit);
                                    chunkNumber++) {
                                documentBatch.add(new Document(
                                        chunks.get(chunkNumber),
                                        Map.of(
                                                "repositoryId", repositoryId,
                                                "repositoryName", repository.getName(),
                                                "filePath", downloaded.path(),
                                                "blobSha", changedFiles.get(fileIndex)
                                                        .file().path("sha").asText(""),
                                                "chunkNumber", chunkNumber,
                                                "indexScope", "quick",
                                                "indexVersion", indexVersion
                                        )
                                ));

                                if (documentBatch.size()
                                        >= MAX_DOCUMENTS_PER_BATCH) {
                                    int progress = embeddingProgress(
                                            indexedDocuments,
                                            estimatedTotalChunks);
                                    if (!embeddingStageStarted
                                            || progress > lastProgress) {
                                        lastProgress = Math.max(
                                                lastProgress, progress);
                                        updateStatus(
                                                repository,
                                                "IN_PROGRESS",
                                                lastProgress,
                                                indexedDocuments,
                                                statusTotalChunks,
                                                "Creating quick Q&A embeddings",
                                                null);
                                        embeddingStageStarted = true;
                                    }
                                    indexedDocuments +=
                                            addDocumentsWithTokenLimitFallback(
                                                    documentBatch);
                                    documentBatch.clear();
                                    progress = embeddingProgress(
                                            indexedDocuments,
                                            estimatedTotalChunks);
                                    if (progress > lastProgress) {
                                        lastProgress = progress;
                                        updateStatus(
                                                repository,
                                                "IN_PROGRESS",
                                                progress,
                                                indexedDocuments,
                                                statusTotalChunks,
                                                "Creating quick Q&A embeddings",
                                                null);
                                    }
                                }

                            }
                        }
                    }
                }
            }

            if (!documentBatch.isEmpty()) {
                if (!embeddingStageStarted) {
                    updateStatus(
                            repository,
                            "IN_PROGRESS",
                            lastProgress,
                            indexedDocuments,
                            statusTotalChunks,
                            "Creating quick Q&A embeddings",
                            null);
                }
                indexedDocuments +=
                        addDocumentsWithTokenLimitFallback(documentBatch);
                documentBatch.clear();
            }

            if (indexedDocuments + reusableChunkCount == 0) {
                throw new IllegalStateException(
                        "No indexable source files were found.");
            }
            // Only switch unchanged files to the new version after all changed files
            // have embedded successfully. If indexing fails, the old version remains usable.
            if (!reusableFiles.isEmpty()) {
                updateReusableFileMetadata(
                        repositoryId, indexVersion, reusableFiles);
            }
            indexedDocuments += (int) Math.min(reusableChunkCount, Integer.MAX_VALUE - indexedDocuments);
            updateStatus(
                    repository,
                    "IN_PROGRESS",
                    95,
                    indexedDocuments,
                    statusTotalChunks,
                    "Cleaning up outdated index entries",
                    null);
        } catch (RuntimeException exception) {
            try {
                deleteIndexVersion(repositoryId, indexVersion);
            } catch (RuntimeException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }

        long cleanupStartedNanos = System.nanoTime();
        jdbcTemplate.update(
                "DELETE FROM vector_store " +
                        "WHERE metadata->>'repositoryId' = ? " +
                        "AND metadata->>'indexVersion' IS DISTINCT FROM ?",
                repositoryId.toString(),
                indexVersion
        );

        updateStatus(
                repository,
                "COMPLETED",
                100,
                indexedDocuments,
                indexedDocuments,
                "Quick Q&A preparation complete",
                null
        );
        long elapsedMillis = (System.nanoTime() - indexingStartedNanos) / 1_000_000;
        long cleanupMillis = (System.nanoTime() - cleanupStartedNanos) / 1_000_000;
        log.info("Quick repository indexing completed: repositoryId={}, eligibleFiles={}, selectedFiles={}, reusedFiles={}, chunks={}, cleanupMs={}, elapsedMs={}",
                repositoryId, indexableFiles.size(), selectedFiles.size(),
                reusableFiles.size(), indexedDocuments, cleanupMillis, elapsedMillis);

        return indexedDocuments;
    }

    private void updateReusableFileMetadata(
            Long repositoryId,
            String indexVersion,
            Map<String, String> reusableFiles) {
        String valuesClause = String.join(
                ", ", java.util.Collections.nCopies(reusableFiles.size(), "(?, ?)"));
        String sql = "UPDATE vector_store AS indexed " +
                "SET metadata = (jsonb_set(jsonb_set(indexed.metadata::jsonb, " +
                "'{indexVersion}', to_jsonb(CAST(? AS text)), true), " +
                "'{indexScope}', '\"quick\"'::jsonb, true))::json " +
                "FROM (VALUES " + valuesClause + ") AS reusable(file_path, blob_sha) " +
                "WHERE indexed.metadata->>'repositoryId' = ? " +
                "AND indexed.metadata->>'filePath' = reusable.file_path " +
                "AND indexed.metadata->>'blobSha' = reusable.blob_sha";
        List<Object> parameters = new ArrayList<>();
        parameters.add(indexVersion);
        reusableFiles.forEach((path, blobSha) -> {
            parameters.add(path);
            parameters.add(blobSha);
        });
        parameters.add(repositoryId.toString());
        jdbcTemplate.update(sql, parameters.toArray());
    }

    private Map<String, ExistingFileIndex> loadExistingFileIndex(Long repositoryId) {
        List<ExistingFileIndex> rows = jdbcTemplate.query(
                "SELECT metadata->>'filePath' AS file_path, metadata->>'blobSha' AS blob_sha, COUNT(*) AS chunk_count " +
                        "FROM vector_store WHERE metadata->>'repositoryId' = ? AND metadata->>'blobSha' IS NOT NULL " +
                        "GROUP BY metadata->>'filePath', metadata->>'blobSha'",
                (rs, rowNum) -> new ExistingFileIndex(
                        rs.getString("file_path"),
                        rs.getString("blob_sha"),
                        rs.getInt("chunk_count")),
                repositoryId.toString());
        Map<String, ExistingFileIndex> indexed = new HashMap<>();
        for (ExistingFileIndex row : rows) {
            ExistingFileIndex previous = indexed.get(row.path());
            if (previous == null || row.chunkCount() > previous.chunkCount()) {
                indexed.put(row.path(), row);
            }
        }
        return indexed;
    }

    private record ExistingFileIndex(String path, String blobSha, int chunkCount) {
    }

    static List<SelectedIndexFile> selectQuickIndexFiles(
            List<JsonNode> files,
            int maxChunks) {
        if (maxChunks <= 0 || files.isEmpty()) {
            return List.of();
        }

        List<JsonNode> prioritizedFiles = new ArrayList<>(files);
        prioritizedFiles.sort(
                Comparator.comparingInt((JsonNode file) ->
                                quickIndexPriority(file.path("path").asText()))
                        .thenComparing(file -> file.path("path").asText()));

        Map<String, Integer> selectedChunkLimits = new LinkedHashMap<>();
        int selectedChunks = 0;
        for (JsonNode file : prioritizedFiles) {
            if (selectedChunks >= maxChunks) {
                break;
            }
            if (estimateChunkCount(file.path("size").asLong(0)) > 0) {
                selectedChunkLimits.put(file.path("path").asText(), 1);
                selectedChunks++;
            }
        }

        while (selectedChunks < maxChunks) {
            boolean addedChunk = false;
            for (JsonNode file : prioritizedFiles) {
                if (selectedChunks >= maxChunks) {
                    break;
                }
                String path = file.path("path").asText();
                int selectedForFile = selectedChunkLimits.getOrDefault(path, 0);
                long availableChunks = estimateChunkCount(
                        file.path("size").asLong(0));
                if (selectedForFile < availableChunks) {
                    selectedChunkLimits.put(path, selectedForFile + 1);
                    selectedChunks++;
                    addedChunk = true;
                }
            }
            if (!addedChunk) {
                break;
            }
        }

        List<SelectedIndexFile> selectedFiles = new ArrayList<>();
        for (JsonNode file : prioritizedFiles) {
            String path = file.path("path").asText();
            Integer chunkLimit = selectedChunkLimits.get(path);
            if (chunkLimit != null) {
                selectedFiles.add(new SelectedIndexFile(file, chunkLimit));
            }
        }
        return selectedFiles;
    }

    private static int quickIndexPriority(String path) {
        String lowerPath = path.toLowerCase();
        String fileName = lowerPath.substring(lowerPath.lastIndexOf('/') + 1);
        if (fileName.startsWith("readme")) {
            return 0;
        }
        if (Set.of("pom.xml", "build.gradle", "build.gradle.kts",
                "package.json", "pyproject.toml", "go.mod",
                "cargo.toml", "settings.gradle", "settings.gradle.kts")
                .contains(fileName)) {
            return 1;
        }
        if (Set.of("application.java", "main.java", "app.java",
                "index.js", "index.ts", "main.py", "main.go")
                .contains(fileName)) {
            return 2;
        }
        if (lowerPath.contains("/src/main/") || lowerPath.startsWith("src/main/")) {
            return 3;
        }
        if (lowerPath.contains("/src/") || lowerPath.startsWith("src/")) {
            return 4;
        }
        if (lowerPath.contains("config")) {
            return 5;
        }
        if (lowerPath.startsWith("docs/") || lowerPath.contains("/docs/")) {
            return 6;
        }
        return 7;
    }

    record SelectedIndexFile(JsonNode file, int chunkLimit) {
    }

    private DownloadedFile downloadFile(
            String owner,
            String repository,
            String branch,
            String path) {
        String rawUrl = "https://raw.githubusercontent.com/"
                + owner + "/" + repository + "/" + branch + "/" + path;
        String content = rawContentClient.get()
                .uri(URI.create(rawUrl))
                .retrieve()
                .body(String.class);
        return new DownloadedFile(path, content);
    }

    private record DownloadedFile(String path, String content) {
    }

    private void deleteIndexVersion(Long repositoryId, String indexVersion) {
        jdbcTemplate.update(
                "DELETE FROM vector_store " +
                        "WHERE metadata->>'repositoryId' = ? " +
                        "AND metadata->>'indexVersion' = ?",
                repositoryId.toString(),
                indexVersion
        );
    }

    private void updateStatus(
            RepositoryInfo repository,
            String status,
            int progress,
            Integer chunks,
            Integer totalChunks,
            String stage,
            String error) {

        repository.setIndexingStatus(status);
        repository.setIndexingStage(stage);
        repository.setIndexingProgress(progress);
        repository.setIndexedChunks(chunks);
        repository.setIndexingTotalChunks(totalChunks);
        repository.setIndexingError(error);

        if ("IN_PROGRESS".equals(status) || "PENDING".equals(status)) {
            repository.setIndexingStartedAt(LocalDateTime.now());
            repository.setIndexingCompletedAt(null);
        }

        if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
            repository.setIndexingCompletedAt(LocalDateTime.now());
        }

        repositoryInfoRepository.save(repository);
    }

    static List<String> chunkContent(String content) {
        List<String> chunks = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return chunks;
        }

        for (int start = 0; start < content.length();) {
            int end = Math.min(start + MAX_CHUNK_CHARS, content.length());
            chunks.add(content.substring(start, end));
            if (end == content.length()) {
                break;
            }
            start = end - CHUNK_OVERLAP_CHARS;
        }
        return chunks;
    }

    static long estimateChunkCount(long size) {
        if (size <= MAX_CHUNK_CHARS) {
            return size > 0 ? 1 : 0;
        }
        long chunkStep = MAX_CHUNK_CHARS - CHUNK_OVERLAP_CHARS;
        return 1 + (size - MAX_CHUNK_CHARS + chunkStep - 1) / chunkStep;
    }

    private int embeddingProgress(int indexedChunks, long estimatedTotalChunks) {
        if (estimatedTotalChunks <= 0) {
            return 5;
        }
        return 5 + (int) Math.min(
                89,
                (long) indexedChunks * 89 / estimatedTotalChunks);
    }

    int addDocumentsWithTokenLimitFallback(List<Document> documents) {
        try {
            vectorStore.add(documents);
            return documents.size();
        } catch (RuntimeException exception) {
            if (!isEmbeddingInputTooLarge(exception)) {
                throw exception;
            }

            if (documents.size() > 1) {
                int midpoint = documents.size() / 2;
                return addDocumentsWithTokenLimitFallback(
                                documents.subList(0, midpoint))
                        + addDocumentsWithTokenLimitFallback(
                                documents.subList(midpoint, documents.size()));
            }

            Document document = documents.get(0);
            String text = document.getText();
            if (text.length() <= 1) {
                throw exception;
            }

            int midpoint = text.length() / 2;
            int overlap = Math.min(CHUNK_OVERLAP_CHARS, midpoint);
            int leftEnd = Math.min(text.length(), midpoint + overlap / 2);
            int rightStart = Math.max(0, midpoint - overlap / 2);
            String subChunkPath = String.valueOf(
                    document.getMetadata().getOrDefault("subChunkNumber", ""));
            Document left = createSubChunk(
                    document, text.substring(0, leftEnd), subChunkPath + "0");
            Document right = createSubChunk(
                    document, text.substring(rightStart), subChunkPath + "1");
            return addDocumentsWithTokenLimitFallback(List.of(left))
                    + addDocumentsWithTokenLimitFallback(List.of(right));
        }
    }

    private Document createSubChunk(
            Document original,
            String text,
            String subChunkNumber) {
        Map<String, Object> metadata = new HashMap<>(original.getMetadata());
        metadata.put("subChunkNumber", subChunkNumber);
        return new Document(text, metadata);
    }

    private boolean isEmbeddingInputTooLarge(RuntimeException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null) {
                continue;
            }
            String normalizedMessage = message.toLowerCase();
            if ((normalizedMessage.contains("token")
                    && (normalizedMessage.contains("too large")
                    || normalizedMessage.contains("exceed")
                    || normalizedMessage.contains("limit")))
                    || normalizedMessage.contains("maximum context length")) {
                return true;
            }
        }
        return false;
    }

    boolean isIndexable(String path, long size) {
        String lower = path.toLowerCase();

        if (size <= 0 || size > 200_000) {
            return false;
        }

        for (String segment : lower.split("/")) {
            if (EXCLUDED_PATH_SEGMENTS.contains(segment)) {
                return false;
            }
        }

        return lower.endsWith(".java")
                || lower.endsWith(".js")
                || lower.endsWith(".jsx")
                || lower.endsWith(".ts")
                || lower.endsWith(".tsx")
                || lower.endsWith(".py")
                || lower.endsWith(".go")
                || lower.endsWith(".html")
                || lower.endsWith(".css")
                || lower.endsWith(".sql")
                || lower.endsWith(".xml")
                || lower.endsWith(".yml")
                || lower.endsWith(".yaml")
                || lower.endsWith(".properties")
                || lower.endsWith(".md")
                || lowerendsWithConfig(lower);
    }

    private boolean lowerendsWithConfig(String path) {
        return path.endsWith(".json")
                || path.endsWith(".gradle")
                || path.endsWith(".gradle.kts")
                || path.endsWith(".toml");
    }

    private String[] parseGitHubUrl(String githubUrl) {
        URI uri = URI.create(githubUrl);

        if (!"https".equals(uri.getScheme())
                || !"github.com".equalsIgnoreCase(uri.getHost())) {
            throw new IllegalArgumentException("Invalid GitHub URL");
        }

        String[] parts = uri.getPath()
                .replaceAll("^/|/$", "")
                .split("/");

        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid GitHub repository URL");
        }

        return new String[]{
                parts[0],
                parts[1].replaceFirst("\\.git$", "")
        };
    }
}
