
package com.repoinsight.repository.service;

import com.repoinsight.repository.entity.AnalysisReport;
import com.repoinsight.repository.entity.AnalysisStatus;
import com.repoinsight.repository.entity.RepositoryInfo;
import com.repoinsight.repository.exception.RepositoryNotFoundException;
import com.repoinsight.repository.repository.AnalysisReportRepository;
import com.repoinsight.repository.repository.RepositoryInfoRepository;
import org.springframework.stereotype.Service;

@Service
public class AnalysisService {

    private final RepositoryInfoRepository repositoryInfoRepository;
    private final AnalysisReportRepository analysisReportRepository;
    private final AnalysisExecutionService analysisExecutionService;
    private final AnalysisFailureService analysisFailureService;

    public AnalysisService(
            RepositoryInfoRepository repositoryInfoRepository,
            AnalysisReportRepository analysisReportRepository,
            AnalysisExecutionService analysisExecutionService,
            AnalysisFailureService analysisFailureService) {
        this.repositoryInfoRepository = repositoryInfoRepository;
        this.analysisReportRepository = analysisReportRepository;
        this.analysisExecutionService = analysisExecutionService;
        this.analysisFailureService = analysisFailureService;
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

    public AnalysisReport markReportFailed(Long reportId) {
        return analysisFailureService.markFailed(reportId);
    }


    public AnalysisReport analyzeReport(Long reportId) {
        if (reportId == null || reportId <= 0) {
            throw new IllegalArgumentException("reportId must be a positive number");
        }

        AnalysisReport existing = analysisReportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Analysis report not found: " + reportId));

        // Kafka can redeliver messages. Completed reports are idempotently ignored.
        // IN_PROGRESS is deliberately not skipped: a previous consumer may have
        // crashed after persisting that status, and redelivery should be able to retry.
        if (existing.getStatus() == AnalysisStatus.COMPLETED) {
            return existing;
        }

        try {
            // Persist IN_PROGRESS before starting the long-running work.
            analysisFailureService.markInProgress(reportId);
            return analysisExecutionService.executeAnalysis(reportId);
        } catch (RuntimeException ex) {
            System.err.println(
                    "Analysis failed for report ID " + reportId
                            + ": " + ex.getMessage());

            try {
                return analysisFailureService.markFailed(reportId);
            } catch (RuntimeException failureEx) {
                ex.addSuppressed(failureEx);
                throw new IllegalStateException(
                        "Analysis failed and status persistence also failed "
                                + "for report ID " + reportId,
                        ex);
            }
        }
    }
}
