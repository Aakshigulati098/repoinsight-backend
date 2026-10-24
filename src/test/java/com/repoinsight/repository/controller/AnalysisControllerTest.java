package com.repoinsight.repository.controller;

import com.repoinsight.repository.entity.AnalysisReport;
import com.repoinsight.repository.entity.AnalysisStatus;
import com.repoinsight.repository.entity.RepositoryInfo;
import com.repoinsight.repository.kafka.AnalysisRequestProducer;
import com.repoinsight.repository.repository.AnalysisReportRepository;
import com.repoinsight.repository.service.AnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AnalysisControllerTest {

    @Test
    void publishesCreatedPendingReportId() {
        AnalysisRequestProducer producer = mock(AnalysisRequestProducer.class);
        AnalysisReportRepository reportRepository = mock(AnalysisReportRepository.class);
        AnalysisService service = mock(AnalysisService.class);
        AnalysisController controller = new AnalysisController(producer, reportRepository, service);

        AnalysisReport report = new AnalysisReport();
        report.setId(101L);
        report.setStatus(AnalysisStatus.PENDING);
        RepositoryInfo repository = new RepositoryInfo();
        report.setRepositoryInfo(repository);
        when(service.createPendingReport(5L)).thenReturn(report);

        controller.analyzeRepository(5L);

        verify(service).createPendingReport(5L);
        verify(producer).sendAnalysisRequest(report.getId());
    }

    @Test
    void propagatesKafkaPublishFailure() {
        AnalysisRequestProducer producer = mock(AnalysisRequestProducer.class);
        AnalysisReportRepository reportRepository = mock(AnalysisReportRepository.class);
        AnalysisService service = mock(AnalysisService.class);
        AnalysisController controller = new AnalysisController(producer, reportRepository, service);

        AnalysisReport report = new AnalysisReport();
        report.setId(102L);
        when(service.createPendingReport(5L)).thenReturn(report);
        doThrow(new IllegalStateException("Kafka unavailable"))
                .when(producer).sendAnalysisRequest(report.getId());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.analyzeRepository(5L));
        assertEquals(503, exception.getStatusCode().value());
        verify(service).markReportFailed(102L);
    }
}
