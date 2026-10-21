
package com.repoinsight.repository.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record GithubImportRequest(

        @NotBlank(message = "GitHub URL is required")
        @Pattern(
                regexp = "^https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/?$",
                message = "Provide a valid GitHub repository URL, such as https://github.com/spring-projects/spring-boot"
        )
        String githubUrl

) {}
