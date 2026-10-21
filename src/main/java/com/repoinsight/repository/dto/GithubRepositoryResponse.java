package com.repoinsight.repository.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GithubRepositoryResponse(
        String name,
        @JsonProperty("html_url") String htmlUrl,
        String description,
        @JsonProperty("stargazers_count") Integer stars,
        String language,
        @JsonProperty("default_branch") String defaultBranch
) {}