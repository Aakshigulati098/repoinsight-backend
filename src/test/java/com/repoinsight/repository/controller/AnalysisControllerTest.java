package com.repoinsight.repository.controller;

import com.repoinsight.repository.entity.AnalysisReport;
import com.repoinsight.repository.entity.AnalysisStatus;
import com.repoinsight.repository.entity.RepositoryInfo;
import com.repoinsight.repository.exception.RepositoryNotFoundException;
import com.repoinsight.repository.kafka.AnalysisRequestProducer;
import com.repoinsight.repository.repository.AnalysisReportRepository;
import com.repoinsight.repository.service.AnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AnalysisController.class)
class AnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnalysisService analysisService;

    @MockitoBean
    private AnalysisRequestProducer analysisRequestProducer;

    @MockitoBean
    private AnalysisReportRepository analysisReportRepository;

    @Test
    void analyzeRepository_Success_ReturnsCreatedReport() throws Exception {
        AnalysisReport report = new AnalysisReport();
        report.setRepositoryInfo(repository());
        report.setStatus(AnalysisStatus.PENDING);
        when(analysisService.createPendingReport(7L)).thenReturn(report);

        mockMvc.perform(post("/api/analysis/repositories/7"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void analyzeRepository_MissingRepository_ReturnsNotFoundError() throws Exception {
        when(analysisService.createPendingReport(404L))
                .thenThrow(new RepositoryNotFoundException(404L));

        mockMvc.perform(post("/api/analysis/repositories/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error")
                        .value("Repository not found with id: 404"));
    }

    @Test
    void getReport_Success_ReturnsReportBody() throws Exception {
        AnalysisReport report = new AnalysisReport();
        report.setRepositoryInfo(repository());
        report.setStatus(AnalysisStatus.FAILED);
        report.setFileCount(0);
        when(analysisReportRepository.findById(12L)).thenReturn(Optional.of(report));

        mockMvc.perform(get("/api/analysis/12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.fileCount").value(0))
                .andExpect(jsonPath("$.repositoryInfo.name").value("demo-repo"));
    }

    @Test
    void getReport_WhenReportDoesNotExist_ReturnsNotFound() throws Exception {
        when(analysisReportRepository.findById(404L))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/analysis/404"))
                .andExpect(status().isNotFound());
    }

    private RepositoryInfo repository() {
        RepositoryInfo repository = new RepositoryInfo();
        repository.setName("demo-repo");
        repository.setGithubUrl("https://github.com/demo/repo");
        return repository;
    }
}
