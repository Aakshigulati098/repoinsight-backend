package com.repoinsight.repository.service;

import com.repoinsight.repository.dto.GithubImportRequest;
import com.repoinsight.repository.dto.GithubRepositoryResponse;
import com.repoinsight.repository.entity.RepositoryInfo;
import com.repoinsight.repository.exception.RepositoryNotFoundException;
import com.repoinsight.repository.repository.AnalysisReportRepository;
import com.repoinsight.repository.repository.RepositoryInfoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RepositoryInfoService {

    private final RepositoryInfoRepository repositoryInfoRepository;
    private final AnalysisReportRepository analysisReportRepository;
    private final GithubClientService githubClientService;
    private final RepositoryIndexService repositoryIndexService;

    public RepositoryInfoService(
            RepositoryInfoRepository repositoryInfoRepository,
            AnalysisReportRepository analysisReportRepository,
            GithubClientService githubClientService,
            RepositoryIndexService repositoryIndexService) {
        this.repositoryInfoRepository = repositoryInfoRepository;
        this.analysisReportRepository = analysisReportRepository;
        this.githubClientService = githubClientService;
        this.repositoryIndexService = repositoryIndexService;
    }

    public RepositoryInfo saveRepository(RepositoryInfo repositoryInfo) {
        return repositoryInfoRepository.save(repositoryInfo);
    }

    public List<RepositoryInfo> getAllRepositories() {
        return repositoryInfoRepository.findAll();
    }

    public RepositoryInfo getRepositoryById(Long id) {
        return repositoryInfoRepository.findById(id)
                .orElseThrow(() -> new RepositoryNotFoundException(id));
    }

    public RepositoryInfo updateRepository(
            Long id, RepositoryInfo updatedInfo) {

        RepositoryInfo existing = getRepositoryById(id);

        existing.setName(updatedInfo.getName());
        existing.setGithubUrl(updatedInfo.getGithubUrl());
        existing.setDescription(updatedInfo.getDescription());

        return repositoryInfoRepository.save(existing);
    }

    @Transactional
    public void deleteRepository(Long id) {
        RepositoryInfo repository = getRepositoryById(id);

        // Remove analysis reports to avoid foreign-key violations.
        analysisReportRepository.deleteByRepositoryInfo_Id(id);

        repositoryInfoRepository.delete(repository);
    }

    public RepositoryInfo importFromGitHub(GithubImportRequest request) {
        GithubRepositoryResponse github =
                githubClientService.fetchRepository(request.githubUrl());

        RepositoryInfo repository = new RepositoryInfo();

        repository.setId(repositoryIndexService.findSmallestAvailableId());
        repository.setName(github.name());
        repository.setGithubUrl(github.htmlUrl());
        repository.setDescription(github.description());
        repository.setStars(github.stars());
        repository.setLanguage(github.language());
        repository.setDefaultBranch(github.defaultBranch());

        return repositoryInfoRepository.save(repository);
    }
}