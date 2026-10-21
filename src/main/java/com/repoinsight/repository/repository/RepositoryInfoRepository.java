package com.repoinsight.repository.repository;

import com.repoinsight.repository.entity.RepositoryInfo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositoryInfoRepository
        extends JpaRepository<RepositoryInfo, Long> {
}