package com.repoinsight.repository.controller;

import com.repoinsight.repository.dto.GithubImportRequest;
import com.repoinsight.repository.entity.RepositoryInfo;
import com.repoinsight.repository.exception.RepositoryNotFoundException;
import com.repoinsight.repository.service.RepositoryInfoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RepositoryInfoController.class)
class RepositoryInfoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RepositoryInfoService repositoryInfoService;

    @Test
    void createRepository_ValidRequest_ReturnsCreatedRepository() throws Exception {
        when(repositoryInfoService.saveRepository(any(RepositoryInfo.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/api/repositories")
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "demo-repo",
                                  "githubUrl": "https://github.com/demo/repo",
                                  "description": "Demo project"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("demo-repo"))
                .andExpect(jsonPath("$.githubUrl").value("https://github.com/demo/repo"))
                .andExpect(jsonPath("$.description").value("Demo project"));
    }

    @Test
    void createRepository_InvalidRequest_ReturnsBadRequestWithValidationError() throws Exception {
        mockMvc.perform(post("/api/repositories")
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "",
                                  "githubUrl": "https://gitlab.com/demo/repo",
                                  "description": "Demo project"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void importFromGitHub_InvalidUrl_ReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/repositories/import")
                        .contentType("application/json")
                        .content("""
                                {"githubUrl": "https://example.com/not-a-github-repo"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("githubUrl")));
    }

    @Test
    void importFromGitHub_ValidRequest_ReturnsCreatedRepository() throws Exception {
        RepositoryInfo imported = new RepositoryInfo();
        imported.setName("repo");
        imported.setGithubUrl("https://github.com/demo/repo");
        imported.setDescription("Imported");
        when(repositoryInfoService.importFromGitHub(any(GithubImportRequest.class)))
                .thenReturn(imported);

        mockMvc.perform(post("/api/repositories/import")
                        .contentType("application/json")
                        .content("""
                                {"githubUrl": "https://github.com/demo/repo"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("repo"))
                .andExpect(jsonPath("$.description").value("Imported"));
    }

    @Test
    void getAllRepositories_ReturnsRepositoriesInResponseBody() throws Exception {
        RepositoryInfo repository = new RepositoryInfo();
        repository.setName("demo-repo");
        repository.setGithubUrl("https://github.com/demo/repo");
        when(repositoryInfoService.getAllRepositories()).thenReturn(List.of(repository));

        mockMvc.perform(get("/api/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("demo-repo"))
                .andExpect(jsonPath("$[0].githubUrl").value("https://github.com/demo/repo"));
    }

    @Test
    void getRepositoryById_MissingRepository_ReturnsNotFoundErrorBody() throws Exception {
        when(repositoryInfoService.getRepositoryById(404L))
                .thenThrow(new RepositoryNotFoundException(404L));

        mockMvc.perform(get("/api/repositories/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Repository not found with id: 404"));
    }

    @Test
    void createRepository_ValidRequest_DelegatesToService() throws Exception {
        when(repositoryInfoService.saveRepository(any(RepositoryInfo.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/api/repositories")
                        .contentType("application/json")
                        .content("""
                                {
                                  "name": "demo-repo",
                                  "githubUrl": "https://github.com/demo/repo"
                                }
                                """))
                .andExpect(status().isOk());

        verify(repositoryInfoService).saveRepository(any(RepositoryInfo.class));
    }
}
