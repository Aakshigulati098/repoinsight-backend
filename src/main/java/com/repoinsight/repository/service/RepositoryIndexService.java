
package com.repoinsight.repository.service;

import com.repoinsight.repository.entity.RepositoryInfo;
import com.repoinsight.repository.repository.RepositoryInfoRepository;
import org.springframework.scheduling.annotation.Async;
import tools.jackson.databind.JsonNode;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class RepositoryIndexService {

    private static final int MAX_CHUNK_CHARS = 300;
    private static final int CHUNK_OVERLAP_CHARS = 50;

    private final RepositoryInfoRepository repositoryInfoRepository;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final RestClient githubClient;

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

        updateStatus(repository, "IN_PROGRESS", 0, 0, null);
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

        List<Document> documents = new ArrayList<>();
        // Tag this indexing run so new chunks can be distinguished from the
        // currently active index until the replacement has been stored.
        String indexVersion = UUID.randomUUID().toString();

        for (JsonNode item : treeResponse.path("tree")) {
            if (!"blob".equals(item.path("type").asText())) {
                continue;
            }

            String path = item.path("path").asText();

            if (!isIndexable(path, item.path("size").asLong(0))) {
                continue;
            }

            try {
                String rawUrl = "https://raw.githubusercontent.com/"
                        + owner + "/" + repo + "/"
                        + finalBranch + "/" + path;

                String content = RestClient.create()
                        .get()
                        .uri(URI.create(rawUrl))
                        .retrieve()
                        .body(String.class);

                if (content == null || content.isBlank()) {
                    continue;
                }

                List<String> chunks = chunkContent(content);
                for (int chunkNumber = 0; chunkNumber < chunks.size(); chunkNumber++) {
                    documents.add(new Document(
                            chunks.get(chunkNumber),
                            Map.of(
                                    "repositoryId", repositoryId,
                                    "repositoryName", repository.getName(),
                                    "filePath", path,
                                    "chunkNumber", chunkNumber,
                                    "indexVersion", indexVersion
                            )
                    ));
                }
            } catch (Exception exception) {
                throw exception;
            }
        }

        if (documents.isEmpty()) {
            throw new IllegalStateException(
                    "No indexable source files were found.");
        }
        try {
            vectorStore.add(documents);
        } catch (RuntimeException exception) {
            deleteIndexVersion(repositoryId, indexVersion);
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
                documents.size(),
                null
        );

        return documents.size();
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
            String error) {

        repository.setIndexingStatus(status);
        repository.setIndexingProgress(progress);
        repository.setIndexedChunks(chunks);
        repository.setIndexingError(error);

        if ("IN_PROGRESS".equals(status)) {
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

    private boolean isIndexable(String path, long size) {
        String lower = path.toLowerCase();

        if (size <= 0 || size > 200_000) {
            return false;
        }

        if (lower.startsWith(".git/")
                || lower.startsWith("node_modules/")
                || lower.startsWith("build/")
                || lower.startsWith("target/")
                || lower.contains("/node_modules/")) {
            return false;
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
