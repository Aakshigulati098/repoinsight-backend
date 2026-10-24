package com.repoinsight.repository.service;

import com.repoinsight.repository.dto.RepositoryTreeResponse;
import com.repoinsight.repository.entity.AnalysisReport;
import com.repoinsight.repository.entity.AnalysisStatus;
import com.repoinsight.repository.entity.RepositoryInfo;
import com.repoinsight.repository.exception.RepositoryNotFoundException;
import com.repoinsight.repository.repository.AnalysisReportRepository;
import com.repoinsight.repository.repository.RepositoryInfoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisServiceTest {

    @Mock
    private RepositoryInfoRepository repositoryInfoRepository;

    @Mock
    private AnalysisReportRepository analysisReportRepository;

    @Mock
    private GithubClientService gitHubClientService;

    @Mock
    private AiSummaryService aiSummaryService;

    private AnalysisService service;

    @BeforeEach
    void setUp() {
        AnalysisExecutionService executionService =
                new AnalysisExecutionService(
                        analysisReportRepository,
                        repositoryInfoRepository,
                        gitHubClientService,
                        aiSummaryService);
        AnalysisFailureService failureService =
                new AnalysisFailureService(analysisReportRepository);

        service = new AnalysisService(
                repositoryInfoRepository,
                analysisReportRepository,
                executionService,
                failureService);
    }

    @Test
    void analyzeRepository_WhenRepositoryMissing_ThrowsRepositoryNotFoundException() {
        when(repositoryInfoRepository.findById(15L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.analyzeRepository(15L))
                .isInstanceOf(RepositoryNotFoundException.class)
                .hasMessageContaining("15");
    }

    @Test
    void analyzeRepository_WhenRepositoryExists_GeneratesSummaryAndDiagram() {
        RepositoryInfo repository = new RepositoryInfo();
        repository.setName("demo-repo");
        repository.setGithubUrl("https://github.com/demo/demo-repo");
        repository.setDescription("Demo repository");
        repository.setDefaultBranch("main");

        when(repositoryInfoRepository.findById(7L)).thenReturn(Optional.of(repository));
        stubReportPersistence();
        when(gitHubClientService.fetchReadme("https://github.com/demo/demo-repo")).thenReturn("# Demo\n\nSample README");
        when(gitHubClientService.analyzeTree("https://github.com/demo/demo-repo", "main"))
                .thenReturn(new RepositoryTreeResponse(3, List.of("pom.xml"), List.of("Java", "Maven"), List.of("src/App.java"), false));
        when(aiSummaryService.generateSummary(
                "demo-repo",
                "Demo repository",
                "# Demo\n\nSample README",
                "[\"Java\",\"Maven\"]",
                "[\"pom.xml\"]"))
                .thenReturn("Generated summary");
        when(aiSummaryService.generateArchitectureDiagram(
                "demo-repo",
                "Demo repository",
                "# Demo\n\nSample README",
                "[\"Java\",\"Maven\"]",
                "[\"pom.xml\"]"))
                .thenReturn("flowchart TD\nA[Application]");

        AnalysisReport result = service.analyzeRepository(7L);

        assertThat(result.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(result.getAiSummary()).isEqualTo("Generated summary");
        assertThat(result.getArchitectureDiagram()).isEqualTo("flowchart TD\nA[Application]");
        assertThat(result.getImportantFiles()).isEqualTo("[\"pom.xml\"]");
        assertThat(result.getTechnologies()).isEqualTo("[\"Java\",\"Maven\"]");
    }

    @Test
    void analyzeRepository_WhenGithubFails_PersistsFailedReport() {
        RepositoryInfo repository = repository();
        when(repositoryInfoRepository.findById(7L)).thenReturn(Optional.of(repository));
        stubReportPersistence();
        when(gitHubClientService.fetchReadme(repository.getGithubUrl()))
                .thenThrow(new IllegalStateException("GitHub unavailable"));

        AnalysisReport result = service.analyzeRepository(7L);

        assertThat(result.getStatus()).isEqualTo(AnalysisStatus.FAILED);
    }

    @Test
    void analyzeRepository_WhenGeminiFails_PersistsFailedReport() {
        RepositoryInfo repository = repository();
        when(repositoryInfoRepository.findById(7L)).thenReturn(Optional.of(repository));
        stubReportPersistence();
        when(gitHubClientService.fetchReadme(repository.getGithubUrl())).thenReturn("# Demo");
        when(gitHubClientService.analyzeTree(repository.getGithubUrl(), repository.getDefaultBranch()))
                .thenReturn(new RepositoryTreeResponse(1, List.of(), List.of(), List.of("README.md"), false));
        when(aiSummaryService.generateSummary(
                "demo-repo", "Demo repository", "# Demo", "[]", "[]"))
                .thenThrow(new IllegalStateException("AI model unavailable"));

        AnalysisReport result = service.analyzeRepository(7L);

        assertThat(result.getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(result.getReadmeContent()).isEqualTo("# Demo");
    }

    private RepositoryInfo repository() {
        RepositoryInfo repository = new RepositoryInfo();
        repository.setName("demo-repo");
        repository.setGithubUrl("https://github.com/demo/demo-repo");
        repository.setDescription("Demo repository");
        repository.setDefaultBranch("main");
        return repository;
    }

    private void stubReportPersistence() {
        AtomicReference<AnalysisReport> persistedReport =
                new AtomicReference<>();
        when(analysisReportRepository.save(any(AnalysisReport.class)))
                .thenAnswer(invocation -> {
                    AnalysisReport report = invocation.getArgument(0);
                    ReflectionTestUtils.setField(report, "id", 100L);
                    persistedReport.set(report);
                    return report;
                });
        when(analysisReportRepository.findById(100L))
                .thenAnswer(invocation ->
                        Optional.ofNullable(persistedReport.get()));
    }
}
