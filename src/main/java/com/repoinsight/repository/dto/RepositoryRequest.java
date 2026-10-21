package com.repoinsight.repository.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RepositoryRequest(

        @NotBlank(message = "Repository name is required")
        @Size(max = 100, message = "Name cannot exceed 100 characters")
        String name,

        @NotBlank(message = "GitHub URL is required")
        @Pattern(
                regexp = "^https://github\\.com/[^/\\s]+/[^/\\s]+/?$",
                message = "Provide a valid GitHub repository URL"
        )
        String githubUrl,

        @Size(max = 1000, message = "Description cannot exceed 1000 characters")
        String description

) {}
