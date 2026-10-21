
package com.repoinsight.repository.controller;

import com.repoinsight.repository.entity.AnalysisReport;
import com.repoinsight.repository.kafka.AnalysisRequestProducer;
import com.repoinsight.repository.repository.AnalysisReportRepository;

import com.repoinsight.repository.service.AnalysisService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

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
    public AnalysisReport analyzeRepository(
            @PathVariable("repositoryId") Long repositoryId) {

        AnalysisReport report =
                analysisService.createPendingReport(repositoryId);

        analysisRequestProducer.sendAnalysisRequest(report.getId());

        return report;
    }

    @GetMapping
    public List<AnalysisReport> getAllReports() {
        return analysisReportRepository.findAll();
    }

    @GetMapping("/{reportId}")
    public AnalysisReport getReport(
            @PathVariable("reportId") Long reportId) {
        return analysisReportRepository.findById(reportId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Analysis report not found: " + reportId));
    }

    // delete mapping to delete a report by id
    @DeleteMapping("/{reportId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteReport(@PathVariable("reportId") Long reportId) {
        analysisReportRepository.deleteById(reportId);
    }
}
