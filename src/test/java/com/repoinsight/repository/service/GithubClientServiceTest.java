package com.repoinsight.repository.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GithubClientServiceTest {

    private GithubClientService service;
    private RestClient restClient;
    private RestClient.RequestHeadersUriSpec requestHeadersUriSpec;
    private RestClient.ResponseSpec responseSpec;

    @BeforeEach
    void setUp() {
        restClient = mock(RestClient.class);
        requestHeadersUriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString(), any(), any())).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.retrieve()).thenReturn(responseSpec);

        service = new GithubClientService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "restClient", restClient);
    }

    @Test
    void fetchRepository_ValidGithubUrl_ReturnsRepositoryMetadata() {
        var response = new com.repoinsight.repository.dto.GithubRepositoryResponse(
                "Hello-World",
                "https://github.com/octocat/Hello-World",
                "Sample repository",
                42,
                "Java",
                "main");

        when(responseSpec.body(com.repoinsight.repository.dto.GithubRepositoryResponse.class)).thenReturn(response);

        var repository = service.fetchRepository("https://github.com/octocat/Hello-World");

        assertThat(repository.name()).isEqualTo("Hello-World");
        assertThat(repository.htmlUrl()).isEqualTo("https://github.com/octocat/Hello-World");
        assertThat(repository.stars()).isEqualTo(42);
        assertThat(repository.defaultBranch()).isEqualTo("main");
    }

    @Test
    void fetchRepository_InvalidGitHubUrl_ThrowsException() {
        assertThatThrownBy(() -> service.fetchRepository("https://gitlab.com/octocat/Hello-World"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid GitHub repository URL");
    }

    @Test
    void fetchRepository_InvalidRepositoryPath_ThrowsExceptionWithoutCallingGithub() {
        assertThatThrownBy(() -> service.fetchRepository("https://github.com/octocat"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL must look like");
    }

    @Test
    void fetchRepository_WhenGithubFails_PropagatesExternalServiceFailure() {
        when(responseSpec.body(com.repoinsight.repository.dto.GithubRepositoryResponse.class))
                .thenThrow(new IllegalStateException("GitHub unavailable"));

        assertThatThrownBy(() -> service.fetchRepository("https://github.com/octocat/Hello-World"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GitHub unavailable");
    }
}
