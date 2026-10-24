package com.repoinsight.repository.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubRepositoryResponse(
        String name,
        @JsonProperty("html_url") String htmlUrl,
        String description,
        @JsonProperty("stargazers_count") Integer stars,
        @JsonProperty("forks_count") Integer forks,
        @JsonProperty("open_issues_count") Integer openIssues,
        String language,
        @JsonProperty("default_branch") String defaultBranch,
        GithubOwner owner,
        GithubLicense license,
        List<String> topics
) {
    public GithubRepositoryResponse(
            String name,
            String htmlUrl,
            String description,
            Integer stars,
            String language,
            String defaultBranch
    ) {
        this(name, htmlUrl, description, stars, 0, 0, language, defaultBranch, null, null, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GithubOwner(
            String login,
            @JsonProperty("avatar_url") String avatarUrl,
            @JsonProperty("html_url") String htmlUrl,
            String type
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GithubLicense(
            String key,
            String name,
            @JsonProperty("spdx_id") String spdxId
    ) {}
}