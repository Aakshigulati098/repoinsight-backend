package com.repoinsight.repository.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryChatServiceTest {

    @Test
    void repositoryFileIndexReportsFileTypesAndRanksMatchingPaths() {
        String index = RepositoryChatService.buildRepositoryFileIndex(
                List.of(
                        "README.md",
                        "src/main/java/example/SecurityConfig.java",
                        "web/styles/main.css",
                        "web/index.html",
                        "build.gradle"),
                "Does this repository have a CSS file?");

        assertThat(index)
                .contains("Total files: 5")
                .contains(".css (1)")
                .contains("web/styles/main.css");
        assertThat(index.indexOf("web/styles/main.css"))
                .isLessThan(index.indexOf("README.md"));
    }

    @Test
    void projectStructureSummaryUsesRepositoryTreeWithoutModelGeneration() {
        String summary = RepositoryChatService.buildProjectStructureSummary(
                List.of(
                        "README.md",
                        "pom.xml",
                        "java/pom.xml",
                        "java/core/src/Main.java",
                        "python/pyproject.toml"));

        assertThat(summary)
                .contains("`java/` — 2 files")
                .contains("`python/` — 1 file")
                .contains("Key root files: README.md, pom.xml")
                .contains("GitHub file tree");
    }
}
