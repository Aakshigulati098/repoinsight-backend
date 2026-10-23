package com.repoinsight.repository.controller;

import com.repoinsight.repository.entity.AnalysisReport;
import com.repoinsight.repository.kafka.AnalysisRequestProducer;
import com.repoinsight.repository.repository.AnalysisReportRepository;
import com.repoinsight.repository.service.AnalysisService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final AnalysisRequestProducer analysisRequestProducer;
    private final AnalysisReportRepository analysisReportRepository;
    private final AnalysisService analysisService;

    public AnalysisController(
            AnalysisRequestProducer analysisRequestProducer,
            AnalysisReportRepository analysisReportRepository,
            AnalysisService analysisService) {
        this.analysisRequestProducer = analysisRequestProducer;
        this.analysisReportRepository = analysisReportRepository;
        this.analysisService = analysisService;
    }

    @PostMapping("/repositories/{repositoryId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AnalysisReport analyzeRepository(@PathVariable("repositoryId") Long repositoryId) {
        if (repositoryId == null || repositoryId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "repositoryId must be a positive number");
        }

        AnalysisReport report = analysisService.createPendingReport(repositoryId);
        try {
            analysisRequestProducer.sendAnalysisRequest(report.getId());
            return report;
        } catch (RuntimeException publishException) {
            // Do not leave a report in PENDING when its Kafka event was not published.
            try {
                analysisService.markReportFailed(report.getId());
            } catch (RuntimeException statusException) {
                publishException.addSuppressed(statusException);
            }
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not queue analysis request. The report has been marked FAILED when possible.",
                    publishException);
        }
    }

    @GetMapping
    public List<AnalysisReport> getAllReports() {
        return analysisReportRepository.findAll();
    }

    @GetMapping("/{reportId}")
    public AnalysisReport getReport(@PathVariable("reportId") Long reportId) {
        return analysisReportRepository.findById(reportId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Analysis report not found: " + reportId));
    }

    @DeleteMapping("/{reportId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteReport(@PathVariable("reportId") Long reportId) {
        if (!analysisReportRepository.existsById(reportId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Analysis report not found: " + reportId);
        }
        analysisReportRepository.deleteById(reportId);
    }
}
