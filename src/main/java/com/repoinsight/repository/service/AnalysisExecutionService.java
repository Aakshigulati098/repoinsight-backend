
package com.repoinsight.repository.service;

import com.repoinsight.repository.dto.RepositoryTreeResponse;
import com.repoinsight.repository.entity.AnalysisReport;
import com.repoinsight.repository.entity.AnalysisStatus;
import com.repoinsight.repository.repository.AnalysisReportRepository;
import com.repoinsight.repository.repository.RepositoryInfoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Service
public class AnalysisExecutionService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisExecutionService.class);

    private final AnalysisReportRepository analysisReportRepository;
    private final RepositoryInfoRepository repositoryInfoRepository;
    private final GithubClientService gitHubClientService;
    private final AiSummaryService aiSummaryService;

    public AnalysisExecutionService(
            AnalysisReportRepository analysisReportRepository,
            RepositoryInfoRepository repositoryInfoRepository,
            GithubClientService gitHubClientService,
            AiSummaryService aiSummaryService) {
        this.analysisReportRepository = analysisReportRepository;
        this.repositoryInfoRepository = repositoryInfoRepository;
        this.gitHubClientService = gitHubClientService;
        this.aiSummaryService = aiSummaryService;
    }

    @Transactional
    public AnalysisReport executeAnalysis(Long reportId) {
        AnalysisReport report = analysisReportRepository
                .findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Analysis report not found: " + reportId));

        var repository = report.getRepositoryInfo();
        String githubUrl = repository.getGithubUrl();
        String defaultBranch = repository.getDefaultBranch();
        String repositoryName = repository.getName();
        String repositoryDescription = repository.getDescription();

        // These GitHub requests are independent, so fetch them concurrently.
        var readmeFuture = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                gitHubClientService.fetchReadme(githubUrl));
        var treeFuture = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                gitHubClientService.analyzeTree(
                        githubUrl,
                        defaultBranch));

        String readme = readmeFuture.join();
        RepositoryTreeResponse tree = treeFuture.join();

        // Step 3: Save repository analysis data.
        report.setReadmeContent(readme);
        report.setFileCount(tree.fileCount());
        report.setImportantFiles(toJsonArray(tree.importantFiles()));
        report.setTechnologies(toJsonArray(tree.technologies()));
        report.setSampleFilePaths(toJsonArray(tree.sampleFilePaths()));
        report.setTreeTruncated(tree.truncated());

        String readmeContent = report.getReadmeContent();
        String technologies = report.getTechnologies();
        String importantFiles = report.getImportantFiles();
        var summaryFuture = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                aiSummaryService.generateSummary(
                        repositoryName,
                        repositoryDescription,
                        readmeContent,
                        technologies,
                        importantFiles));
        var diagramFuture = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                aiSummaryService.generateArchitectureDiagram(
                        repositoryName,
                        repositoryDescription,
                        readmeContent,
                        technologies,
                        importantFiles));

        report.setAiSummary(resolveModelOutput(
                summaryFuture,
                "AI-generated summary is unavailable because the configured model request failed. "
                        + buildEvidenceSummary(
                                repositoryName,
                                repositoryDescription,
                                report.getFileCount(),
                                technologies),
                reportId,
                "summary"));
        report.setArchitectureDiagram(resolveModelOutput(
                diagramFuture,
                buildEvidenceDiagram(repository.getName(), report.getFileCount()),
                reportId,
                "architecture diagram"));
        report.setStatus(AnalysisStatus.COMPLETED);

        return analysisReportRepository.save(report);
    }

    private String resolveModelOutput(
            CompletableFuture<String> output,
            String fallback,
            Long reportId,
            String outputName) {
        try {
            String result = output.join();
            if (result == null || result.isBlank()) {
                log.warn("AI analysis returned empty {}: reportId={}",
                        outputName, reportId);
                return fallback;
            }
            return result;
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() == null
                    ? exception
                    : exception.getCause();
            log.warn("AI analysis {} generation failed: reportId={}, cause={}",
                    outputName, reportId, cause.toString());
            return fallback;
        }
    }

    private String buildEvidenceSummary(
            String repositoryName,
            String description,
            Integer fileCount,
            String technologies) {
        String name = repositoryName == null || repositoryName.isBlank()
                ? "unnamed repository"
                : repositoryName;
        StringBuilder summary = new StringBuilder(
                "A GitHub-evidence-based overview of ")
                .append(name).append(".\n\n")
                .append("**Overall Impression:** AI-generated analysis is ")
                .append("unavailable, so this report is limited to repository ")
                .append("metadata rather than verified source-level behavior.")
                .append("\n\n**Detailed Breakdown:**\n");
        if (description != null && !description.isBlank()) {
            summary.append("1. **Project Overview:** ")
                    .append(description.trim());
        } else {
            summary.append("1. **Project Overview:** No repository description ")
                    .append("was available.");
        }
        if (fileCount != null) {
            summary.append("\n2. **Repository Contents:** The GitHub file tree ")
                    .append("contains ").append(fileCount).append(" files.");
        }
        if (technologies != null && !technologies.isBlank()) {
            String readableTechnologies = technologies
                    .replace("[", "")
                    .replace("]", "")
                    .replace("\"", "")
                    .trim();
            if (!readableTechnologies.isBlank()) {
                summary.append("\n3. **Detected Technologies:** The repository uses or references ")
                        .append(readableTechnologies).append('.');
            }
        }
        summary.append("\n\n**Evidence Analysis & Key Considerations:**\n")
                .append("- This overview is based on GitHub repository metadata; ")
                .append("it does not verify individual source-code behavior.")
                .append("\n\n**Potential Improvements:** A full project review ")
                .append("could not be generated because the configured AI ")
                .append("model request failed.")
                .append("\n\nOverall, the available evidence provides a basic ")
                .append("repository overview but not a detailed code analysis.");
        return summary.toString();
    }

    private String buildEvidenceDiagram(String repositoryName, Integer fileCount) {
        String safeName = repositoryName == null || repositoryName.isBlank()
                ? "Repository"
                : repositoryName.replaceAll("[^A-Za-z0-9 _.-]", "").trim();
        if (safeName.isBlank()) {
            safeName = "Repository";
        }
        String countLabel = fileCount == null
                ? "GitHub file tree"
                : "GitHub file tree: " + fileCount + " files";
        return """
                flowchart TD
                    A["%s"]
                    B["%s"]
                    A --> B
                    %% AI architecture generation unavailable; this shows repository metadata only.
                """.formatted(safeName, countLabel).trim();
    }

    private String toJsonArray(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r") + "\"")
                .collect(java.util.stream.Collectors.joining(
                        ",", "[", "]"));
    }
}
