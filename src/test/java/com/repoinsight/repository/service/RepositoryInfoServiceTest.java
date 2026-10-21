package com.repoinsight.repository.service;

import com.repoinsight.repository.dto.GithubImportRequest;
import com.repoinsight.repository.dto.GithubRepositoryResponse;
import com.repoinsight.repository.entity.RepositoryInfo;
import com.repoinsight.repository.exception.RepositoryNotFoundException;
import com.repoinsight.repository.repository.RepositoryInfoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepositoryInfoServiceTest {

    @Mock
    private RepositoryInfoRepository repositoryInfoRepository;

    @Mock
    private GithubClientService githubClientService;

    @InjectMocks
    private RepositoryInfoService service;

    @Test
    void saveRepository_DelegatesToRepository() {
        RepositoryInfo repositoryInfo = new RepositoryInfo();
        repositoryInfo.setName("demo-project");

        when(repositoryInfoRepository.save(repositoryInfo)).thenReturn(repositoryInfo);

        RepositoryInfo saved = service.saveRepository(repositoryInfo);

        assertThat(saved.getName()).isEqualTo("demo-project");
        verify(repositoryInfoRepository).save(repositoryInfo);
    }

    @Test
    void getRepositoryById_MissingId_ThrowsRepositoryNotFoundException() {
        when(repositoryInfoRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRepositoryById(99L))
                .isInstanceOf(RepositoryNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void updateRepository_UpdatesExistingRecord() {
        RepositoryInfo existing = new RepositoryInfo();
        existing.setName("old-name");
        existing.setGithubUrl("https://github.com/old/repo");
        existing.setDescription("old description");

        RepositoryInfo updated = new RepositoryInfo();
        updated.setName("new-name");
        updated.setGithubUrl("https://github.com/new/repo");
        updated.setDescription("new description");

        when(repositoryInfoRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(repositoryInfoRepository.save(any(RepositoryInfo.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RepositoryInfo result = service.updateRepository(1L, updated);

        assertThat(result.getName()).isEqualTo("new-name");
        assertThat(result.getGithubUrl()).isEqualTo("https://github.com/new/repo");
        assertThat(result.getDescription()).isEqualTo("new description");
    }

    @Test
    void deleteRepository_MissingId_ThrowsRepositoryNotFoundException() {
        when(repositoryInfoRepository.findById(42L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteRepository(42L))
                .isInstanceOf(RepositoryNotFoundException.class)
                .hasMessageContaining("42");
    }

    @Test
    void importFromGitHub_MapsGithubMetadataToRepository() {
        GithubImportRequest request = new GithubImportRequest("https://github.com/octocat/Hello-World");
        GithubRepositoryResponse githubResponse = new GithubRepositoryResponse(
                "Hello-World",
                "https://github.com/octocat/Hello-World",
                "Friendly hello",
                120,
                "Java",
                "main");

        when(githubClientService.fetchRepository(request.githubUrl())).thenReturn(githubResponse);
        when(repositoryInfoRepository.save(any(RepositoryInfo.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RepositoryInfo imported = service.importFromGitHub(request);

        assertThat(imported.getName()).isEqualTo("Hello-World");
        assertThat(imported.getGithubUrl()).isEqualTo("https://github.com/octocat/Hello-World");
        assertThat(imported.getDescription()).isEqualTo("Friendly hello");
        assertThat(imported.getStars()).isEqualTo(120);
        assertThat(imported.getLanguage()).isEqualTo("Java");
        assertThat(imported.getDefaultBranch()).isEqualTo("main");
    }

    @Test
    void getAllRepositories_ReturnsRepositoryList() {
        RepositoryInfo repo = new RepositoryInfo();
        repo.setName("sample-project");
        when(repositoryInfoRepository.findAll()).thenReturn(List.of(repo));

        List<RepositoryInfo> repositories = service.getAllRepositories();

        assertThat(repositories).hasSize(1);
        assertThat(repositories.getFirst().getName()).isEqualTo("sample-project");
    }
}
