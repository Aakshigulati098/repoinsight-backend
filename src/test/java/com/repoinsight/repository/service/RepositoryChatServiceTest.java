package com.repoinsight.repository.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Test
    void folderQuestionResolvesMatchingRepositoryDirectoryWithoutModelGeneration() {
        String directory = RepositoryChatService.findQuestionDirectory(
                List.of(
                        "README.md",
                        "specification/README.md",
                        "specification/src/Specification.java",
                        "localization/es/specification/README.md"),
                "What does the specification folder do?");

        assertThat(directory).isEqualTo("specification");
    }

    @Test
    void folderQuestionResolvesDirectoryMentionWithoutFolderKeyword() {
        String directory = RepositoryChatService.findQuestionDirectory(
                List.of(
                        "microservices-self-registration/README.md",
                        "microservices-self-registration/src/Registration.java",
                        "ambassador/README.md"),
                "What does microservices-self-registration tell us?");

        assertThat(directory).isEqualTo("microservices-self-registration");
        assertThat(RepositoryChatService.isDirectoryDescriptionQuestion(
                "what does Array/ contains?")).isTrue();
    }

    @Test
    void repositoryOverviewRecognizesCommonQuestionVariations() {
        assertThat(RepositoryChatService.isRepositoryOverviewQuestion(
                "what does this repository does?")).isTrue();
        assertThat(RepositoryChatService.isRepositoryOverviewQuestion(
                "What does this repo do?")).isTrue();
        assertThat(RepositoryChatService.isRepositoryOverviewQuestion(
                "What does this project do?")).isTrue();
        assertThat(RepositoryChatService.isRepositoryOverviewQuestion(
                "Explain the project structure")).isFalse();
        assertThat(RepositoryChatService.isRepositoryOverviewQuestion(
                "what is the repository about?")).isTrue();
        assertThat(RepositoryChatService.isRepositoryOverviewQuestion(
                "what is the project about?")).isTrue();
    }

    @Test
    void genericConceptQuestionsAreNotMisroutedToDirectoryAnswers() {
        assertThat(RepositoryChatService.isDirectoryDescriptionQuestion(
                "what is authentication?")).isFalse();
        assertThat(RepositoryChatService.isDirectoryDescriptionQuestion(
                "what is springboot?")).isFalse();
        assertThat(RepositoryChatService.isDirectoryDescriptionQuestion(
                "what is the most easy thing in this repo?")).isFalse();
        assertThat(RepositoryChatService.isDirectoryDescriptionQuestion(
                "what is abstract-document?")).isTrue();
        assertThat(RepositoryChatService.isDirectoryDescriptionQuestion(
                "what does microservices-self-registration tells us?")).isTrue();
    }

    @Test
    void fileCountQuestionsAreHandledFromTheGithubTree() {
        assertThat(RepositoryChatService.isFileCountQuestion(
                "this project has total how many files?")).isTrue();
        assertThat(RepositoryChatService.isFileCountQuestion(
                "How many files are in the repository?")).isTrue();
        assertThat(RepositoryChatService.isFileCountQuestion(
                "What files handle authentication?")).isFalse();
    }

    @Test
    void readmeSummaryPrefersDescriptionFromYamlFrontMatter() {
        String summary = RepositoryChatService.extractReadmeSummary("""
                ---
                title: "Repository Pattern"
                description: "A pattern that abstracts data access and persistence."
                category: Data access
                ---

                Longer documentation follows.
                """);

        assertThat(summary)
                .isEqualTo("A pattern that abstracts data access and persistence.");
    }

    @Test
    void readmeQuestionsAreRoutedToTheRootReadme() {
        assertThat(RepositoryChatService.isReadmeQuestion(
                "what does README of this project say?")).isTrue();
        assertThat(RepositoryChatService.isReadmeQuestion(
                "summarize the project readme")).isTrue();
        assertThat(RepositoryChatService.isReadmeQuestion(
                "how is authentication handled?")).isFalse();
    }

    @Test
    void databaseQuestionsAreRoutedToRepositorySchemaEvidence() {
        assertThat(RepositoryChatService.isDatabaseQuestion(
                "does it have any db collections?")).isTrue();
        assertThat(RepositoryChatService.isDatabaseQuestion(
                "where are the database tables defined?")).isTrue();
        assertThat(RepositoryChatService.isDatabaseQuestion(
                "what does the Array folder contain?")).isFalse();
    }

    @Test
    void authenticationQuestionsUseRepositoryEvidenceScan() {
        assertThat(RepositoryChatService.isAuthenticationQuestion(
                "is any authentication handled?")).isTrue();
        assertThat(RepositoryChatService.isAuthenticationQuestion(
                "is there auth code in this project?")).isTrue();
    }

    @Test
    void authenticationEvidenceAnswerReportsFoundLinesWithoutModelGeneration() {
        String answer = RepositoryChatService.buildAuthenticationEvidenceAnswer(
                Map.of(
                        "src/main/java/example/SecurityConfig.java",
                        "public class SecurityConfig {\n"
                                + "  SecurityFilterChain filterChain() { return null; }\n"
                                + "}"),
                1,
                false);

        assertThat(answer)
                .contains("authentication/security-related code indicators")
                .contains("`src/main/java/example/SecurityConfig.java` L2")
                .contains("SecurityFilterChain");
    }

    @Test
    void authenticationEvidenceAnswerStatesNoMatchAndScanScope() {
        String answer = RepositoryChatService.buildAuthenticationEvidenceAnswer(
                Map.of("src/main/java/ArrayUtils.java", "class ArrayUtils {}"),
                1,
                false);

        assertThat(answer)
                .contains("I found no clear authentication or authorization implementation")
                .contains("1 production source/configuration files scanned")
                .contains("not proof that no dynamic or externally provided authentication exists");
    }

    @Test
    void authenticationQuestionPrioritizesAuthAndSecuritySourcePaths() {
        int authPathScore = RepositoryChatService.pathQuestionScore(
                "src/main/java/example/AuthController.java",
                "is there any authentication code written in this project?");
        int securityPathScore = RepositoryChatService.pathQuestionScore(
                "src/main/java/example/SecurityConfig.java",
                "is there any authentication code written in this project?");
        int unrelatedPathScore = RepositoryChatService.pathQuestionScore(
                "examples/python/batch/README.md",
                "is there any authentication code written in this project?");

        assertThat(authPathScore).isGreaterThan(unrelatedPathScore);
        assertThat(securityPathScore).isGreaterThan(unrelatedPathScore);
    }

    @Test
    void modelFailureFallbackReportsRelevantSourceLinesInsteadOfOnlyFailing() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("src/main/java/example/SecurityConfig.java", """
                package example;
                @Configuration
                public class SecurityConfig {
                    SecurityFilterChain filterChain() { return null; }
                }
                """);

        Map<String, Object> response = RepositoryChatService.buildEvidenceFallback(
                7L,
                "is there authentication code in this project?",
                sources,
                "The AI model did not respond.");

        assertThat(response.get("answer").toString())
                .contains("Evidence in `src/main/java/example/SecurityConfig.java`")
                .contains("SecurityFilterChain");
        assertThat(response.get("sources"))
                .isEqualTo(sources.keySet());
    }

    @Test
    void folderQuestionCanResolveNestedDirectoryWhenItIsNamedExplicitly() {
        String directory = RepositoryChatService.findQuestionDirectory(
                List.of(
                        "localization/es/specification/README.md",
                        "localization/fr/specification/README.md"),
                "Explain the localization/es/specification directory");

        assertThat(directory).isEqualTo("localization/es/specification");
    }
}
