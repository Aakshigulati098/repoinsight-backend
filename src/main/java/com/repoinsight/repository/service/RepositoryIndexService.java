
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
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;

@Service
public class RepositoryIndexService {

    private static final int MAX_CHUNK_CHARS = 300;
    private static final int CHUNK_OVERLAP_CHARS = 50;
    private static final int MAX_DOCUMENTS_PER_BATCH = 16;
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
        this.rawContentClient = RestClient.create();
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

        updateStatus(
                repository,
                "IN_PROGRESS",
                5,
                0,
                0,
                "Downloading source files",
                null);

        // Keep the current index available until every batch in this run succeeds.
        String indexVersion = UUID.randomUUID().toString();
        List<Document> documents = new ArrayList<>();
        try (var downloadExecutor =
                     Executors.newVirtualThreadPerTaskExecutor()) {
            for (int offset = 0; offset < indexableFiles.size();
                    offset += MAX_CONCURRENT_DOWNLOADS) {
                int batchEnd = Math.min(
                        offset + MAX_CONCURRENT_DOWNLOADS,
                        indexableFiles.size());
                List<CompletableFuture<DownloadedFile>> downloads =
                        new ArrayList<>(batchEnd - offset);

                for (int fileIndex = offset; fileIndex < batchEnd; fileIndex++) {
                    String path = indexableFiles.get(fileIndex)
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

                    if (downloaded.content() != null
                            && !downloaded.content().isBlank()) {
                        List<String> chunks = chunkContent(downloaded.content());
                        for (int chunkNumber = 0;
                                chunkNumber < chunks.size();
                                chunkNumber++) {
                            documents.add(new Document(
                                    chunks.get(chunkNumber),
                                    Map.of(
                                            "repositoryId", repositoryId,
                                            "repositoryName", repository.getName(),
                                            "filePath", downloaded.path(),
                                            "chunkNumber", chunkNumber,
                                            "indexVersion", indexVersion
                                    )
                            ));
                        }
                    }

                }

                int progress = 5 + (int) ((long) batchEnd * 40
                        / indexableFiles.size());
                updateStatus(
                        repository,
                        "IN_PROGRESS",
                        progress,
                        0,
                        0,
                        "Downloading source files",
                        null);
            }
        }

        if (documents.isEmpty()) {
            throw new IllegalStateException(
                    "No indexable source files were found.");
        }

        int indexedDocuments = 0;
        int totalDocuments = documents.size();
        int lastProgress = 45;
        updateStatus(
                repository,
                "IN_PROGRESS",
                45,
                0,
                totalDocuments,
                "Embedding and storing source chunks",
                null);
        try {
            for (int offset = 0; offset < documents.size();
                    offset += MAX_DOCUMENTS_PER_BATCH) {
                int batchEnd = Math.min(
                        offset + MAX_DOCUMENTS_PER_BATCH,
                        documents.size());
                List<Document> documentBatch =
                        documents.subList(offset, batchEnd);
                int storedDocuments =
                        addDocumentsWithTokenLimitFallback(documentBatch);
                indexedDocuments += storedDocuments;
                totalDocuments += storedDocuments - documentBatch.size();

                int progress = Math.max(lastProgress, 45 + (int) Math.round(
                        (double) indexedDocuments * 50 / totalDocuments));
                lastProgress = progress;
                updateStatus(
                        repository,
                        "IN_PROGRESS",
                        progress,
                        indexedDocuments,
                        totalDocuments,
                        "Embedding and storing source chunks",
                        null);
            }

            updateStatus(
                    repository,
                    "IN_PROGRESS",
                    95,
                    indexedDocuments,
                    documents.size(),
                    "Finalizing index",
                    null);
        } catch (RuntimeException exception) {
            try {
                deleteIndexVersion(repositoryId, indexVersion);
            } catch (RuntimeException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }

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
                documents.size(),
                "Indexing complete",
                null
        );

        return indexedDocuments;
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
