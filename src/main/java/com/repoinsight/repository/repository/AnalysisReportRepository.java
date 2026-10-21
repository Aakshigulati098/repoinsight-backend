package com.repoinsight.repository.repository;

import com.repoinsight.repository.entity.AnalysisReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisReportRepository
        extends JpaRepository<AnalysisReport, Long> {
    long deleteByRepositoryInfo_Id(Long repositoryId);
}