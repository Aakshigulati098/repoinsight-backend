package com.repoinsight.repository.service;

import tools.jackson.databind.JsonNode;

import com.repoinsight.repository.dto.RepositoryTreeResponse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.repoinsight.repository.dto.GithubRepositoryResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import java.net.URI;

@Service
public class GithubClientService {

    private final RestClient restClient;

    public GithubClientService() {
        this.restClient = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .build();
    }

    public GithubRepositoryResponse fetchRepository(String githubUrl) {
        URI uri = URI.create(githubUrl);

        if (!"https".equals(uri.getScheme())
                || !"github.com".equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null
                || uri.getPort() != -1) {
            throw new IllegalArgumentException("Invalid GitHub repository URL");
        }

        String path = uri.getPath();

        if (path == null) {
            throw new IllegalArgumentException("Invalid GitHub repository URL");
        }

        String[] parts = path.replaceAll("^/|/$", "").split("/");

        if (parts.length != 2
                || parts[0].isBlank()
                || parts[1].isBlank()
                || parts[0].equals(".")
                || parts[1].equals(".")
                || parts[0].equals("..")
                || parts[1].equals("..")) {
            throw new IllegalArgumentException(
                    "URL must look like https://github.com/owner/repository");
        }

        String owner = parts[0];
        String repo = parts[1].replaceFirst("\\.git$", "");

        return restClient.get()
                .uri("/repos/{owner}/{repo}", owner, repo)
                .retrieve()
                .body(GithubRepositoryResponse.class);
    }
    public String fetchReadme(String githubUrl) {
        String[] parts = getOwnerAndRepo(githubUrl);

        JsonNode response = restClient.get()
                .uri("/repos/{owner}/{repo}/readme", parts[0], parts[1])
                .retrieve()
                .body(JsonNode.class);

        if (response == null || response.get("content") == null) {
            return "";
        }

        String encodedContent = response.get("content").asText();
        byte[] decodedBytes = Base64.getMimeDecoder()
                .decode(encodedContent);

        return new String(decodedBytes, StandardCharsets.UTF_8);
    }

    public int countFiles(String githubUrl, String defaultBranch) {
        String[] parts = getOwnerAndRepo(githubUrl);

        JsonNode response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/git/trees/{branch}")
                        .queryParam("recursive", "1")
                        .build(parts[0], parts[1], defaultBranch))
                .retrieve()
                .body(JsonNode.class);

        if (response == null || response.get("tree") == null) {
            return 0;
        }

        JsonNode tree = response.get("tree");

        int count = 0;

        for (JsonNode item : tree) {
            if ("blob".equals(item.path("type").asText())) {
                count++;
            }
        }

        return count;
    }

    private String[] getOwnerAndRepo(String githubUrl) {
        java.net.URI uri = java.net.URI.create(githubUrl);

        if (!"https".equals(uri.getScheme())
                || !"github.com".equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null
                || uri.getPort() != -1) {
            throw new IllegalArgumentException("Invalid GitHub URL");
        }

        String path = uri.getPath();

        if (path == null) {
            throw new IllegalArgumentException("Invalid GitHub URL");
        }
        String[] parts = path.replaceAll("^/|/$", "").split("/");
        if (parts.length != 2
                || parts[0].isBlank()
                || parts[1].isBlank()) {
            throw new IllegalArgumentException(
                    "URL must look like https://github.com/owner/repository");
        }
        String repo = parts[1].replaceFirst("\\.git$", "");
        return new String[]{parts[0], repo};
    }
    public RepositoryTreeResponse analyzeTree(
            String githubUrl,
            String defaultBranch) {

        String[] parts = getOwnerAndRepo(githubUrl);

        JsonNode response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/repos/{owner}/{repo}/git/trees/{branch}")
                        .queryParam("recursive", "1")
                        .build(parts[0], parts[1], defaultBranch))
                .retrieve()
                .body(JsonNode.class);

        if (response == null || !response.has("tree")) {
            throw new IllegalStateException(
                    "GitHub returned no repository file tree");
        }

        JsonNode tree = response.get("tree");

        int fileCount = 0;
        Set<String> importantFiles = new LinkedHashSet<>();
        Set<String> technologies = new LinkedHashSet<>();
        List<String> sampleFilePaths = new ArrayList<>();

        for (JsonNode item : tree) {
            if (!"blob".equals(item.path("type").asText())) {
                continue;
            }

            String path = item.path("path").asText();
            String lowerPath = path.toLowerCase(Locale.ROOT);
            String fileName = lowerPath.substring(
                    lowerPath.lastIndexOf('/') + 1);

            fileCount++;

            // Keep a bounded sample so reports don't become enormous.
            if (sampleFilePaths.size() < 30) {
                sampleFilePaths.add(path);
            }

            // Detect important project and configuration files.
            if (isImportantFile(fileName)) {
                importantFiles.add(path);
            }

            // Detect technologies using filenames and extensions.
            detectTechnologies(fileName, lowerPath, technologies);
        }

        return new RepositoryTreeResponse(
                fileCount,
                List.copyOf(importantFiles),
                List.copyOf(technologies),
                List.copyOf(sampleFilePaths),
                response.path("truncated").asBoolean(false)
        );
    }

    private boolean isImportantFile(String fileName) {
        return Set.of(
                "pom.xml",
                "build.gradle",
                "build.gradle.kts",
                "settings.gradle",
                "settings.gradle.kts",
                "package.json",
                "dockerfile",
                "docker-compose.yml",
                "docker-compose.yaml",
                "compose.yml",
                "compose.yaml",
                "requirements.txt",
                "pyproject.toml",
                "go.mod",
                "cargo.toml",
                "readme.md"
        ).contains(fileName);
    }

    private void detectTechnologies(
            String fileName,
            String path,
            Set<String> technologies) {

        if (fileName.endsWith(".java")) {
            technologies.add("Java");
        }

        if (fileName.equals("pom.xml")) {
            technologies.add("Maven");
        }

        if (fileName.equals("build.gradle")
                || fileName.equals("build.gradle.kts")) {
            technologies.add("Gradle");
        }

        if (fileName.equals("package.json")) {
            technologies.add("Node.js");
            technologies.add("JavaScript/Node ecosystem");
        }

        if (fileName.endsWith(".tsx")
                || fileName.endsWith(".jsx")) {
            technologies.add("React (possible)");
        }

        if (fileName.equals("dockerfile")
                || fileName.equals("docker-compose.yml")
                || fileName.equals("docker-compose.yaml")
                || fileName.equals("compose.yml")
                || fileName.equals("compose.yaml")) {
            technologies.add("Docker");
        }

        if (fileName.equals("requirements.txt")
                || fileName.equals("pyproject.toml")) {
            technologies.add("Python");
        }

        if (fileName.equals("go.mod")) {
            technologies.add("Go");
        }

        if (fileName.equals("cargo.toml")) {
            technologies.add("Rust");
        }

        if (path.contains("src/main/resources/application.properties")
                || path.contains("src/main/resources/application.yml")
                || path.contains("src/main/resources/application.yaml")) {
            technologies.add("Spring Boot (possible)");
        }
    }
}