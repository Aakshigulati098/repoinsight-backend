package com.repoinsight.repository.service;

import com.repoinsight.repository.entity.RepositoryInfo;
import com.repoinsight.repository.repository.RepositoryInfoRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RepositoryChatService {

    private static final int MAX_FILES = 10;
    private static final int MAX_SEED_FILES = 4;
    private static final int MAX_FILE_CHARS = 8000;
    private static final int MAX_CONTEXT_CHARS = 36000;
    private static final Pattern JAVA_IMPORT = Pattern.compile(
            "(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+)(?:\\.\\*)?\\s*;");

    private final RepositoryInfoRepository repositoryInfoRepository;
    private final ChatClient chatClient;
    private final RestClient githubClient;
    private final RestClient rawGitHubClient;

    public RepositoryChatService(
            RepositoryInfoRepository repositoryInfoRepository,
            ChatClient.Builder chatClientBuilder) {

        this.repositoryInfoRepository = repositoryInfoRepository;
        this.chatClient = chatClientBuilder.build();
        this.githubClient = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .build();
        this.rawGitHubClient = RestClient.create();
    }

    public Map<String, Object> ask(Long repositoryId, String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question is required");
        }

        RepositoryInfo repository = repositoryInfoRepository.findById(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Repository not found: " + repositoryId));

        String[] parts = parseGitHubUrl(repository.getGithubUrl());
        String owner = parts[0];
        String repo = parts[1];
        String branch = repository.getDefaultBranch();
        String selectedBranch = branch == null || branch.isBlank() ? "main" : branch;

        JsonNode tree = githubClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/git/trees/{branch}")
                        .queryParam("recursive", "1")
                        .build(owner, repo, selectedBranch))
                .retrieve()
                .body(JsonNode.class);

        if (tree == null || !tree.has("tree")) {
            throw new IllegalStateException("Could not fetch the repository file tree");
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
        candidates.sort(Comparator.comparingInt(path -> filePriority(path, question)));

        // Index source files by their filename so imported Java types can be resolved.
        Map<String, List<String>> pathsByClassName = new LinkedHashMap<>();
        for (String path : candidates) {
            if (path.endsWith(".java")) {
                String className = fileNameWithoutExtension(path);
                pathsByClassName.computeIfAbsent(className, ignored -> new ArrayList<>()).add(path);
            }
        }

        Map<String, String> sources = new LinkedHashMap<>();
        Deque<String> pending = new ArrayDeque<>();
        Set<String> queued = new LinkedHashSet<>();
        int seedCount = 0;
        for (String candidate : candidates) {
            if (seedCount >= MAX_SEED_FILES) {
                break;
            }
            if (isLikelySeed(candidate, question)) {
                pending.addLast(candidate);
                queued.add(candidate);
                seedCount++;
            }
        }
        // Ensure we still have seed files for unusual questions or repositories.
        for (String candidate : candidates) {
            if (seedCount >= MAX_SEED_FILES) {
                break;
            }
            if (queued.add(candidate)) {
                pending.addLast(candidate);
                seedCount++;
            }
        }

        int contextChars = 0;
        while (!pending.isEmpty() && sources.size() < MAX_FILES
                && contextChars < MAX_CONTEXT_CHARS) {
            String path = pending.removeFirst();
            String content = fetchSource(owner, repo, selectedBranch, path);
            if (content == null || content.isBlank()) {
                continue;
            }

            if (content.length() > MAX_FILE_CHARS) {
                content = content.substring(0, MAX_FILE_CHARS);
            }
            int remaining = MAX_CONTEXT_CHARS - contextChars;
            if (remaining <= 0) {
                break;
            }
            if (content.length() > remaining) {
                content = content.substring(0, remaining);
            }
            sources.put(path, content);
            contextChars += content.length();

            // Follow explicit Java imports to pull in services, repositories,
            // DTOs, entities, and other project classes related to the seed files.
            if (path.endsWith(".java")) {
                for (String importedClass : importedSimpleNames(content)) {
                    List<String> matchingPaths = pathsByClassName.get(importedClass);
                    if (matchingPaths == null) {
                        continue;
                    }
                    for (String dependencyPath : matchingPaths) {
                        if (queued.size() >= MAX_FILES) {
                            break;
                        }
                        if (queued.add(dependencyPath)) {
                            pending.addLast(dependencyPath);
                        }
                    }
                }
            }
        }

        if (sources.isEmpty()) {
            throw new IllegalStateException("Could not retrieve source files from GitHub");
        }

        StringBuilder context = new StringBuilder();
        sources.forEach((path, content) -> context
                .append("\n\n--- FILE: ").append(path).append(" ---\n")
                .append(content));

        String answer = chatClient.prompt()
                .system("""
                        You are RepoInsight, a codebase analysis assistant.
                        Answer using only the supplied repository source files.
                        Trace relationships across files when possible, including imports,
                        constructor-injected dependencies, method calls, and request flow.
                        Cite the relevant file paths in your explanation. Distinguish what
                        the code directly shows from reasonable inference. If a dependency
                        or implementation is not included in the supplied files, say so;
                        never invent missing code or behavior.
                        """)
                .user("""
                        Repository: %s
                        GitHub URL: %s

                        Retrieved source files (seed files followed by imported dependencies):
                        %s

                        Question:
                        %s
                        """.formatted(repository.getName(), repository.getGithubUrl(), context, question))
                .call()
                .content();

        return Map.of(
                "repositoryId", repositoryId,
                "answer", answer == null ? "" : answer,
                "sources", sources.keySet()
        );
    }

    private String fetchSource(String owner, String repo, String branch, String path) {
        try {
            String rawUrl = "https://raw.githubusercontent.com/" + owner + "/" + repo
                    + "/" + branch + "/" + path;
            return rawGitHubClient.get().uri(rawUrl).retrieve().body(String.class);
        } catch (Exception exception) {
            // Skip inaccessible files; continue building the available context.
            return null;
        }
    }

    private Set<String> importedSimpleNames(String content) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = JAVA_IMPORT.matcher(content);
        while (matcher.find()) {
            String importedName = matcher.group(1);
            if (importedName == null || importedName.endsWith(".*")) {
                continue;
            }
            int lastDot = importedName.lastIndexOf('.');
            String simpleName = lastDot >= 0
                    ? importedName.substring(lastDot + 1) : importedName;
            if (!simpleName.isBlank()) {
                names.add(simpleName);
            }
        }
        return names;
    }

    private String fileNameWithoutExtension(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return path.substring(slash + 1, dot > slash ? dot : path.length());
    }

    private boolean isLikelySeed(String path, String question) {
        String lower = path.toLowerCase(Locale.ROOT);
        String q = question.toLowerCase(Locale.ROOT);
        boolean flowQuestion = q.contains("request flow") || q.contains("controller")
                || q.contains("service layer") || q.contains("repository layer")
                || q.contains("request to") || q.contains("endpoint")
                || q.contains("how does") || q.contains("trace");
        if (flowQuestion && lower.endsWith(".java")) {
            return lower.contains("controller") || lower.contains("service")
                    || lower.contains("repository") || lower.contains("/dao/");
        }
        return true;
    }

    private boolean isUsefulFile(String path, long size) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (size <= 0 || size > 100_000 || path.contains(" ")) {
            return false;
        }
        if (lower.contains("node_modules/") || lower.contains("/build/")
                || lower.contains("/target/") || lower.startsWith(".git/")) {
            return false;
        }
        return lower.endsWith(".java") || lower.endsWith(".py")
                || lower.endsWith(".js") || lower.endsWith(".jsx")
                || lower.endsWith(".ts") || lower.endsWith(".tsx")
                || lower.endsWith(".md") || lower.endsWith(".xml")
                || lower.endsWith(".yml") || lower.endsWith(".yaml")
                || lower.endsWith(".properties") || lower.endsWith(".gradle")
                || lower.endsWith(".json");
    }

    private int filePriority(String path, String question) {
        String lower = path.toLowerCase(Locale.ROOT);
        String q = question.toLowerCase(Locale.ROOT);
        int score = 50;
        if (lower.endsWith(".java")) score = 20;
        if (lower.endsWith("readme.md")) score = 60;
        if (lower.endsWith("pom.xml") || lower.endsWith("build.gradle")) score = 65;

        boolean flowQuestion = q.contains("request flow") || q.contains("controller")
                || q.contains("service layer") || q.contains("repository layer")
                || q.contains("request to") || q.contains("endpoint")
                || q.contains("how does") || q.contains("trace");
        if (flowQuestion && lower.endsWith(".java")) {
            if (lower.contains("controller")) score = 0;
            else if (lower.contains("service")) score = 5;
            else if (lower.contains("repository") || lower.contains("dao")) score = 10;
            else if (lower.contains("entity") || lower.contains("/model/")) score = 20;
            else if (lower.contains("application.java")) score = 35;
        }
        if ((q.contains("test") || q.contains("unit test"))
                && lower.endsWith(".java")
                && (lower.contains("/test/") || lower.contains("tests"))) {
            score = -5;
        }
        if ((q.contains("configuration") || q.contains("config") || q.contains("database")
                || q.contains("deployment"))
                && (lower.endsWith(".yml") || lower.endsWith(".yaml")
                || lower.endsWith(".properties") || lower.endsWith(".xml")
                || lower.endsWith(".gradle") || lower.endsWith("dockerfile"))) {
            score = 0;
        }
        return score;
    }

    private String[] parseGitHubUrl(String githubUrl) {
        java.net.URI uri = java.net.URI.create(githubUrl);
        if (!"https".equals(uri.getScheme())
                || !"github.com".equalsIgnoreCase(uri.getHost())) {
            throw new IllegalArgumentException("Invalid GitHub repository URL");
        }
        String[] parts = uri.getPath().replaceAll("^/|/$", "").split("/");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid GitHub repository URL");
        }
        return new String[]{parts[0], parts[1].replaceFirst("\\.git$", "")};
    }
}
