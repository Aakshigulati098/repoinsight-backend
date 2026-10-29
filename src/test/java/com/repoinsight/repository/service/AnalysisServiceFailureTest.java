package com.repoinsight.repository.service;

import com.repoinsight.repository.dto.RepositoryTreeResponse;
import com.repoinsight.repository.entity.AnalysisReport;
import com.repoinsight.repository.entity.AnalysisStatus;
import com.repoinsight.repository.entity.RepositoryInfo;
import com.repoinsight.repository.repository.AnalysisReportRepository;
import com.repoinsight.repository.repository.RepositoryInfoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AnalysisServiceFailureTest {

    private RepositoryInfoRepository repositoryInfoRepository;
    private AnalysisReportRepository analysisReportRepository;
    private GithubClientService githubClientService;
    private AiSummaryService aiSummaryService;
    private AnalysisService analysisService;

    @BeforeEach
    void setUp() {
        repositoryInfoRepository = mock(RepositoryInfoRepository.class);
        analysisReportRepository = mock(AnalysisReportRepository.class);
        githubClientService = mock(GithubClientService.class);
        aiSummaryService = mock(AiSummaryService.class);

        AnalysisExecutionService executionService = new AnalysisExecutionService(
                analysisReportRepository,
                repositoryInfoRepository,
                githubClientService,
                aiSummaryService);
        AnalysisFailureService failureService =
                new AnalysisFailureService(analysisReportRepository);
        analysisService = new AnalysisService(repositoryInfoRepository,
                analysisReportRepository, executionService, failureService);
    }

    @Test
    void githubReadmeFailureMarksReportAsFailed() {
        RepositoryInfo repository = new RepositoryInfo();
        repository.setName("sample-repo");
        repository.setGithubUrl("https://github.com/example/sample-repo");
        repository.setDefaultBranch("main");

        AnalysisReport report = new AnalysisReport();
        report.setStatus(AnalysisStatus.PENDING);
        report.setRepositoryInfo(repository);

        when(analysisReportRepository.findById(7L)).thenReturn(Optional.of(report));
        when(analysisReportRepository.save(any(AnalysisReport.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(githubClientService.fetchReadme(repository.getGithubUrl()))
                .thenThrow(new RuntimeException("GitHub unavailable"));

        AnalysisReport result = analysisService.analyzeReport(7L);

        assertEquals(AnalysisStatus.FAILED, result.getStatus());
        verify(analysisReportRepository, atLeastOnce()).save(report);
        verify(githubClientService, times(1))
                .analyzeTree(repository.getGithubUrl(), repository.getDefaultBranch());
        verifyNoInteractions(aiSummaryService);
    }

    @Test
    void successfulAnalysisMarksReportAsCompleted() {
        RepositoryInfo repository = new RepositoryInfo();
        repository.setName("sample-repo");
        repository.setDescription("A sample repository");
        repository.setGithubUrl("https://github.com/example/sample-repo");
        repository.setDefaultBranch("main");

        AnalysisReport report = new AnalysisReport();
        report.setStatus(AnalysisStatus.PENDING);
        report.setRepositoryInfo(repository);

        when(analysisReportRepository.findById(8L)).thenReturn(Optional.of(report));
        when(analysisReportRepository.save(any(AnalysisReport.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(githubClientService.fetchReadme(repository.getGithubUrl()))
                .thenReturn("# Sample Repo");
        when(githubClientService.analyzeTree(repository.getGithubUrl(), "main"))
                .thenReturn(new RepositoryTreeResponse(3, List.of("src/Main.java"),
                        List.of("Java"), List.of("src/Main.java"), false));
        when(aiSummaryService.generateSummary(anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn("Summary");
        when(aiSummaryService.generateArchitectureDiagram(anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn("flowchart TD; A-->B");

        AnalysisReport result = analysisService.analyzeReport(8L);

        assertEquals(AnalysisStatus.COMPLETED, result.getStatus());
        assertEquals("Summary", result.getAiSummary());
        assertEquals("flowchart TD; A-->B", result.getArchitectureDiagram());
        assertEquals(3, result.getFileCount());
    }

    @Test
    void modelFailureKeepsGithubAnalysisAndReturnsExplicitEvidenceFallbacks() {
        RepositoryInfo repository = new RepositoryInfo();
        repository.setName("sample-repo");
        repository.setDescription("A sample repository");
        repository.setGithubUrl("https://github.com/example/sample-repo");
        repository.setDefaultBranch("main");

        AnalysisReport report = new AnalysisReport();
        report.setStatus(AnalysisStatus.PENDING);
        report.setRepositoryInfo(repository);

        when(analysisReportRepository.findById(9L)).thenReturn(Optional.of(report));
        when(analysisReportRepository.save(any(AnalysisReport.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(githubClientService.fetchReadme(repository.getGithubUrl()))
                .thenReturn("# Sample Repo");
        when(githubClientService.analyzeTree(repository.getGithubUrl(), "main"))
                .thenReturn(new RepositoryTreeResponse(
                        3,
                        List.of("src/Main.java"),
                        List.of("Java"),
                        List.of("src/Main.java"),
                        false));
        when(aiSummaryService.generateSummary(anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenThrow(new IllegalStateException("OpenAI request failed"));
        when(aiSummaryService.generateArchitectureDiagram(anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("OpenAI request failed"));

        AnalysisReport result = analysisService.analyzeReport(9L);

        assertEquals(AnalysisStatus.COMPLETED, result.getStatus());
        assertEquals(3, result.getFileCount());
        org.junit.jupiter.api.Assertions.assertTrue(
                result.getAiSummary().contains("AI-generated summary is unavailable"));
        org.junit.jupiter.api.Assertions.assertTrue(
                result.getAiSummary().contains("Java"));
        org.junit.jupiter.api.Assertions.assertFalse(
                result.getAiSummary().contains("Important files identified"));
        org.junit.jupiter.api.Assertions.assertTrue(
                result.getArchitectureDiagram().startsWith("flowchart TD"));
        org.junit.jupiter.api.Assertions.assertTrue(
                result.getArchitectureDiagram().contains("AI architecture generation unavailable"));
    }
}
