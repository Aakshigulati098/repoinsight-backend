
package com.repoinsight.repository.service;

import com.repoinsight.repository.dto.RepositoryTreeResponse;
import com.repoinsight.repository.entity.AnalysisReport;
import com.repoinsight.repository.entity.AnalysisStatus;
import com.repoinsight.repository.repository.AnalysisReportRepository;
import com.repoinsight.repository.repository.RepositoryInfoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AnalysisExecutionService {

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

        // Step 1: Fetch README.
        String readme = gitHubClientService.fetchReadme(
                repository.getGithubUrl());

        // Step 2: Analyze repository file tree.
        RepositoryTreeResponse tree =
                gitHubClientService.analyzeTree(
                        repository.getGithubUrl(),
                        repository.getDefaultBranch());

        // Step 3: Save repository analysis data.
        report.setReadmeContent(readme);
        report.setFileCount(tree.fileCount());
        report.setImportantFiles(toJsonArray(tree.importantFiles()));
        report.setTechnologies(toJsonArray(tree.technologies()));
        report.setSampleFilePaths(toJsonArray(tree.sampleFilePaths()));
        report.setTreeTruncated(tree.truncated());

        // Step 4: Generate AI summary.
        String summary = aiSummaryService.generateSummary(
                repository.getName(),
                repository.getDescription(),
                report.getReadmeContent(),
                report.getTechnologies(),
                report.getImportantFiles());

        report.setAiSummary(summary);

        // Step 5: Generate architecture diagram.
        String diagram =
                aiSummaryService.generateArchitectureDiagram(
                        repository.getName(),
                        repository.getDescription(),
                        report.getReadmeContent(),
                        report.getTechnologies(),
                        report.getImportantFiles());

        report.setArchitectureDiagram(diagram);
        report.setStatus(AnalysisStatus.COMPLETED);

        return analysisReportRepository.save(report);
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
