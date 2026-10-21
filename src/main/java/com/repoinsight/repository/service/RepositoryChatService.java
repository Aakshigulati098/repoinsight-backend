package com.repoinsight.repository.service;

import com.repoinsight.repository.entity.RepositoryInfo;
import com.repoinsight.repository.repository.RepositoryInfoRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RepositoryChatService {

    private static final int MAX_FILES = 6;
    private static final int MAX_FILE_CHARS = 6000;
    private static final int MAX_CONTEXT_CHARS = 24000;

    private final RepositoryInfoRepository repositoryInfoRepository;
    private final ChatClient chatClient;
    private final RestClient githubClient;

    public RepositoryChatService(
            RepositoryInfoRepository repositoryInfoRepository,
            ChatClient.Builder chatClientBuilder) {

        this.repositoryInfoRepository = repositoryInfoRepository;
        this.chatClient = chatClientBuilder.build();

        this.githubClient = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader(
                        "Accept",
                        "application/vnd.github+json")
                .build();
    }

    public Map<String, Object> ask(
            Long repositoryId,
            String question) {

        RepositoryInfo repository = repositoryInfoRepository
                .findById(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Repository not found: " + repositoryId));

        String[] parts = parseGitHubUrl(repository.getGithubUrl());
        String owner = parts[0];
        String repo = parts[1];

        String branch = repository.getDefaultBranch();
        final String selectedBranch =
                branch == null || branch.isBlank() ? "main" : branch;

        JsonNode tree = githubClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/git/trees/{branch}")
                        .queryParam("recursive", "1")
                        .build(owner, repo, selectedBranch))
                .retrieve()
                .body(JsonNode.class);

        if (tree == null || !tree.has("tree")) {
            throw new IllegalStateException(
                    "Could not fetch the repository file tree");
        }

        List<String> candidates = new ArrayList<>();

        for (JsonNode item : tree.path("tree")) {
            if (!"blob".equals(item.path("type").asText())) {
                continue;
            }

            String path = item.path("path").asText();
            long size = item.path("size").asLong(0);

            if (isUsefulFile(path, size)) {
                candidates.add(path);
            }
        }

        // Prefer Java source files for Java/Spring repositories.
        candidates.sort(Comparator.comparingInt(
                path -> filePriority(path, question)));

        Map<String, String> sources = new LinkedHashMap<>();
        int contextChars = 0;

        for (String path : candidates) {
            if (sources.size() >= MAX_FILES
                    || contextChars >= MAX_CONTEXT_CHARS) {
                break;
            }

            try {
                String rawUrl = "https://raw.githubusercontent.com/"
                        + owner + "/" + repo + "/"
                        + selectedBranch + "/" + path;

                String content = RestClient.create()
                        .get()
                        .uri(rawUrl)
                        .retrieve()
                        .body(String.class);

                if (content == null || content.isBlank()) {
                    continue;
                }

                if (content.length() > MAX_FILE_CHARS) {
                    content = content.substring(0, MAX_FILE_CHARS);
                }

                int remaining = MAX_CONTEXT_CHARS - contextChars;

                if (content.length() > remaining) {
                    content = content.substring(0, remaining);
                }

                sources.put(path, content);
                contextChars += content.length();

            } catch (Exception exception) {
                // A file that cannot be downloaded is skipped.
            }
        }

        if (sources.isEmpty()) {
            throw new IllegalStateException(
                    "Could not retrieve source files from GitHub");
        }

        StringBuilder context = new StringBuilder();

        sources.forEach((path, content) -> context
                .append("\n\n--- FILE: ")
                .append(path)
                .append(" ---\n")
                .append(content));

        String answer = chatClient.prompt()
                .system("""
                        You are RepoInsight, a codebase analysis assistant.
                        Answer using the supplied repository source files.
                        Explain your reasoning clearly and refer to file paths
                        when relevant. If the supplied files do not contain
                        enough information, say so instead of inventing details.
                        """)
                .user("""
                        Repository: %s
                        GitHub URL: %s

                        Source files:
                        %s

                        Question:
                        %s
                        """.formatted(
                        repository.getName(),
                        repository.getGithubUrl(),
                        context,
                        question))
                .call()
                .content();

        return Map.of(
                "repositoryId", repositoryId,
                "answer", answer == null ? "" : answer,
                "sources", sources.keySet()
        );
    }

    private boolean isUsefulFile(String path, long size) {
        String lower = path.toLowerCase();

        if (size <= 0 || size > 100_000
                || path.contains(" ")) {
            return false;
        }

        if (lower.contains("node_modules/")
                || lower.contains("/build/")
                || lower.contains("/target/")
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

    private int filePriority(String path, String question) {
        String lower = path.toLowerCase();
        String q = question.toLowerCase();
        int score = 50;
        // Prefer actual application source over documentation/config.
        if (lower.endsWith(".java")) score = 20;
        if (lower.endsWith("readme.md")) score = 60;
        if (lower.endsWith("pom.xml")
                || lower.endsWith("build.gradle")) score = 65;

        // For request-flow questions, prioritize application layers.
        boolean flowQuestion =
                q.contains("request flow")
                        || q.contains("controller")
                        || q.contains("service layer")
                        || q.contains("repository layer")
                        || q.contains("request to")
                        || q.contains("endpoint");

        if (flowQuestion && lower.endsWith(".java")) {
            if (lower.contains("controller")) score = 0;
            else if (lower.contains("service")) score = 5;
            else if (lower.contains("repository")
                    || lower.contains("dao")) score = 10;
            else if (lower.contains("entity")
                    || lower.contains("/model/")) score = 20;
            else if (lower.contains("application.java")) score = 35;
        }

        // Prioritize tests when the question is about testing.
        if ((q.contains("test") || q.contains("unit test"))
                && lower.endsWith(".java")
                && (lower.contains("/test/")
                || lower.contains("tests"))) {
            score = -5;
        }

        // Prioritize configuration when asked about setup.
        if ((q.contains("configuration")
                || q.contains("config")
                || q.contains("database")
                || q.contains("deployment"))
                && (lower.endsWith(".yml")
                || lower.endsWith(".yaml")
                || lower.endsWith(".properties")
                || lower.endsWith(".xml")
                || lower.endsWith(".gradle")
                || lower.endsWith("dockerfile"))) {
            score = 0;
        }
        return score;
    }

    private String[] parseGitHubUrl(String githubUrl) {
        java.net.URI uri = java.net.URI.create(githubUrl);

        if (!"https".equals(uri.getScheme())
                || !"github.com".equalsIgnoreCase(uri.getHost())) {
            throw new IllegalArgumentException(
                    "Invalid GitHub repository URL");
        }

        String[] parts = uri.getPath()
                .replaceAll("^/|/$", "")
                .split("/");

        if (parts.length < 2) {
            throw new IllegalArgumentException(
                    "Invalid GitHub repository URL");
        }

        return new String[] {
                parts[0],
                parts[1].replaceFirst("\\.git$", "")
        };
    }
}