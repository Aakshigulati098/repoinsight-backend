package com.repoinsight.repository.controller;

import com.repoinsight.repository.dto.GithubImportRequest;
import com.repoinsight.repository.dto.RepositoryRequest;
import com.repoinsight.repository.entity.RepositoryInfo;
import com.repoinsight.repository.service.RepositoryInfoService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/repositories")
public class RepositoryInfoController {

    private final RepositoryInfoService repositoryInfoService;

    public RepositoryInfoController(
            RepositoryInfoService repositoryInfoService) {
        this.repositoryInfoService = repositoryInfoService;
    }

    @PostMapping
    public RepositoryInfo createRepository(
            @jakarta.validation.Valid
            @RequestBody RepositoryRequest request) {

        RepositoryInfo info = new RepositoryInfo();
        info.setName(request.name());
        info.setGithubUrl(request.githubUrl());
        info.setDescription(request.description());

        return repositoryInfoService.saveRepository(info);
    }

    @GetMapping
    public List<RepositoryInfo> getAllRepositories() {
        return repositoryInfoService.getAllRepositories();
    }

    @GetMapping("/{id}")
    public RepositoryInfo getRepositoryById(
            @PathVariable("id") Long id) {
        return repositoryInfoService.getRepositoryById(id);
    }

    @PutMapping("/{id}")
    public RepositoryInfo updateRepository(
            @PathVariable("id") Long id,
            @RequestBody RepositoryInfo updatedInfo) {

        return repositoryInfoService.updateRepository(id, updatedInfo);
    }

    @DeleteMapping("/{id}")
    public void deleteRepository(@PathVariable("id") Long id) {
        repositoryInfoService.deleteRepository(id);
    }

    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    public RepositoryInfo importFromGitHub(
            @Valid @RequestBody GithubImportRequest request) {
        return repositoryInfoService.importFromGitHub(request);
    }

}
