package com.repoinsight.repository.service;

import com.repoinsight.repository.dto.RepositoryTreeResponse;
import com.repoinsight.repository.entity.AnalysisReport;
import com.repoinsight.repository.entity.AnalysisStatus;
import com.repoinsight.repository.entity.RepositoryInfo;
import com.repoinsight.repository.exception.RepositoryNotFoundException;
import com.repoinsight.repository.repository.AnalysisReportRepository;
import com.repoinsight.repository.repository.RepositoryInfoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AnalysisService {

    private final RepositoryInfoRepository repositoryInfoRepository;
    private final AnalysisReportRepository analysisReportRepository;
    private final GithubClientService gitHubClientService;
    private final AiSummaryService aiSummaryService;

    public AnalysisService(
            RepositoryInfoRepository repositoryInfoRepository,
            AnalysisReportRepository analysisReportRepository,
            GithubClientService gitHubClientService,
            AiSummaryService aiSummaryService) {
        this.repositoryInfoRepository = repositoryInfoRepository;
        this.analysisReportRepository = analysisReportRepository;
        this.gitHubClientService = gitHubClientService;
        this.aiSummaryService = aiSummaryService;
    }


    public AnalysisReport createPendingReport(Long repositoryId) {

        RepositoryInfo repository = repositoryInfoRepository
                .findById(repositoryId)
                .orElseThrow(
                        () -> new RepositoryNotFoundException(repositoryId));

        AnalysisReport report = new AnalysisReport();
        report.setRepositoryInfo(repository);
        report.setStatus(AnalysisStatus.PENDING);

        return analysisReportRepository.save(report);
    }

    public AnalysisReport analyzeRepository(Long repositoryId) {
        AnalysisReport report = createPendingReport(repositoryId);
        return analyzeReport(report.getId());
    }

    @Transactional
    public AnalysisReport analyzeReport(Long reportId) {

        AnalysisReport report = analysisReportRepository
                .findById(reportId)
                .orElseThrow(() -> new RuntimeException(
                        "Analysis report not found: " + reportId));

        report.setStatus(AnalysisStatus.IN_PROGRESS);
        report = analysisReportRepository.save(report);

        RepositoryInfo repository = report.getRepositoryInfo();

        try {
            // Step 1: Fetch README.
            String readme = gitHubClientService.fetchReadme(
                    repository.getGithubUrl());

            // Step 2: Analyze the repository file tree.
            RepositoryTreeResponse tree =
                    gitHubClientService.analyzeTree(
                            repository.getGithubUrl(),
                            repository.getDefaultBranch());

            // Step 3: Save repository analysis data.
            report.setReadmeContent(readme);
            report.setFileCount(tree.fileCount());
            report.setImportantFiles(
                    toJsonArray(tree.importantFiles()));
            report.setTechnologies(
                    toJsonArray(tree.technologies()));
            report.setSampleFilePaths(
                    toJsonArray(tree.sampleFilePaths()));
            report.setTreeTruncated(tree.truncated());

            // Step 4: Generate the AI summary.
            String summary = aiSummaryService.generateSummary(
                    repository.getName(),
                    repository.getDescription(),
                    report.getReadmeContent(),
                    report.getTechnologies(),
                    report.getImportantFiles());

            report.setAiSummary(summary);

            // Step 5: Generate the architecture diagram.
            String diagram =
                    aiSummaryService.generateArchitectureDiagram(
                            repository.getName(),
                            repository.getDescription(),
                            report.getReadmeContent(),
                            report.getTechnologies(),
                            report.getImportantFiles());

            report.setArchitectureDiagram(diagram);
            report.setStatus(AnalysisStatus.COMPLETED);

        } catch (Exception ex) {
            ex.printStackTrace();
            report.setStatus(AnalysisStatus.FAILED);
        }

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