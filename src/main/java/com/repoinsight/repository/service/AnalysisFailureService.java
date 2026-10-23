
package com.repoinsight.repository.service;

import com.repoinsight.repository.entity.AnalysisReport;
import com.repoinsight.repository.entity.AnalysisStatus;
import com.repoinsight.repository.repository.AnalysisReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalysisFailureService {

    private final AnalysisReportRepository analysisReportRepository;

    public AnalysisFailureService(
            AnalysisReportRepository analysisReportRepository) {
        this.analysisReportRepository = analysisReportRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AnalysisReport markInProgress(Long reportId) {
        AnalysisReport report = findReport(reportId);
        report.setStatus(AnalysisStatus.IN_PROGRESS);
        return analysisReportRepository.save(report);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AnalysisReport markFailed(Long reportId) {
        AnalysisReport report = findReport(reportId);
        report.setStatus(AnalysisStatus.FAILED);
        return analysisReportRepository.save(report);
    }

    private AnalysisReport findReport(Long reportId) {
        return analysisReportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Analysis report not found: " + reportId));
    }
}
