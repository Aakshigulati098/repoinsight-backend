
package com.repoinsight.repository.service;

import com.repoinsight.repository.entity.RepositoryInfo;
import com.repoinsight.repository.repository.RepositoryInfoRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RepositoryIndexService {

    private static final int MAX_FILES = 150;
    private static final int MAX_FILE_SIZE = 80_000;
    // Keep a conservative character limit so code-heavy text stays below the
    // embedding model's 512-token physical batch limit.
    private static final int MAX_CHUNK_CHARS = 500;
    private static final int OVERLAP_CHARS = 75;

    private final RepositoryInfoRepository repositoryRepository;
    private final VectorStore vectorStore;
    private final RestClient githubClient;
    private final RestClient rawClient;

    public RepositoryIndexService(
            RepositoryInfoRepository repositoryRepository,
            VectorStore vectorStore) {
        this.repositoryRepository = repositoryRepository;
        this.vectorStore = vectorStore;

        this.githubClient = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .build();

        this.rawClient = RestClient.create();
    }

    public Map<String, Object> indexRepository(Long repositoryId) {
        RepositoryInfo repository = repositoryRepository
                .findById(repositoryId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Repository not found: " + repositoryId));

        String[] parts = parseGitHubUrl(repository.getGithubUrl());
        String owner = parts[0];
        String repo = parts[1];

        String defaultBranch = repository.getDefaultBranch();
        String branch = defaultBranch == null || defaultBranch.isBlank()
                ? "main" : defaultBranch;

        JsonNode tree = githubClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/git/trees/{branch}")
                        .queryParam("recursive", "1")
                        .build(owner, repo, branch))
                .retrieve()
                .body(JsonNode.class);

        if (tree == null || !tree.has("tree")) {
            throw new IllegalStateException(
                    "Could not fetch the GitHub repository tree");
        }

        List<Document> documents = new ArrayList<>();
        int indexedFiles = 0;

        for (JsonNode item : tree.path("tree")) {
            if (!"blob".equals(item.path("type").asText())) {
                continue;
            }

            String path = item.path("path").asText();
            long size = item.path("size").asLong(0);

            if (!isSupportedFile(path, size)) {
                continue;
            }

            if (indexedFiles >= MAX_FILES) {
                break;
            }

            String content = fetchSource(owner, repo, branch, path);

            if (content == null || content.isBlank()) {
                continue;
            }

            if (content.length() > MAX_FILE_SIZE) {
                content = content.substring(0, MAX_FILE_SIZE);
            }

            List<String> chunks = chunk(content);

            for (int i = 0; i < chunks.size(); i++) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("repositoryId", repositoryId);
                metadata.put("repositoryName", repository.getName());
                metadata.put("filePath", path);
                metadata.put("chunkIndex", i);
                metadata.put("githubUrl", repository.getGithubUrl());

                documents.add(new Document(chunks.get(i), metadata));
            }

            indexedFiles++;
        }

        if (documents.isEmpty()) {
            throw new IllegalStateException(
                    "No supported source files could be indexed");
        }

        // Replace this repository's previous index only after
        // source retrieval and chunking have completed successfully.
        vectorStore.delete("repositoryId == " + repositoryId);
        vectorStore.add(documents);

        return Map.of(
                "repositoryId", repositoryId,
                "indexedFiles", indexedFiles,
                "indexedChunks", documents.size(),
                "status", "COMPLETED"
        );
    }

    private String fetchSource(
            String owner, String repo, String branch, String path) {
        try {
            String url = "https://raw.githubusercontent.com/"
                    + owner + "/" + repo + "/" + branch + "/" + path;

            return rawClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);
        } catch (Exception exception) {
            // Skip unavailable files.
            return null;
        }
    }


    private List<String> chunk(String content) {
        List<String> chunks = new ArrayList<>();

        if (content == null || content.isBlank()) {
            return chunks;
        }

        int start = 0;

        while (start < content.length()) {
            int end = Math.min(
                    start + MAX_CHUNK_CHARS,
                    content.length()
            );

            // Prefer ending at a newline to preserve source-code structure.
            if (end < content.length()) {
                int newline = content.lastIndexOf('\n', end);

                // Avoid producing a very small chunk just to find a newline.
                if (newline > start + MAX_CHUNK_CHARS / 2) {
                    end = newline + 1;
                }
            }

            String text = content.substring(start, end).trim();

            if (!text.isBlank()) {
                chunks.add(text);
            }

            if (end >= content.length()) {
                break;
            }

            // Move forward while retaining a small overlap.
            start = Math.max(start + 1, end - OVERLAP_CHARS);
        }

        return chunks;
    }


    private boolean isSupportedFile(String path, long size) {
        String lower = path.toLowerCase();

        if (size <= 0 || size > MAX_FILE_SIZE || path.contains(" ")) {
            return false;
        }

        if (lower.contains("node_modules/")
                || lower.contains("/build/")
                || lower.contains("/target/")
                || lower.contains("/dist/")
                || lower.startsWith(".git/")) {
            return false;
        }

        return lower.endsWith(".java")
                || lower.endsWith(".py")
                || lower.endsWith(".js")
                || lower.endsWith(".jsx")
                || lower.endsWith(".ts")
                || lower.endsWith(".tsx")
                || lower.endsWith(".md")
                || lower.endsWith(".xml")
                || lower.endsWith(".yml")
                || lower.endsWith(".yaml")
                || lower.endsWith(".properties")
                || lower.endsWith(".gradle")
                || lower.endsWith(".json");
    }

    private String[] parseGitHubUrl(String githubUrl) {
        java.net.URI uri = java.net.URI.create(githubUrl);

        if (!"https".equals(uri.getScheme())
                || !"github.com".equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null
                || uri.getPort() != -1) {
            throw new IllegalArgumentException("Invalid GitHub URL");
        }

        String[] parts = uri.getPath()
                .replaceAll("^/|/$", "")
                .split("/");

        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid GitHub repository URL");
        }

        return new String[]{
                parts[0],
                parts[1].replaceFirst("\\.git$", "")
        };
    }
}
