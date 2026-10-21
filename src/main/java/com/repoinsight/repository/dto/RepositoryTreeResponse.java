package com.repoinsight.repository.dto;

import java.util.List;

public record RepositoryTreeResponse(
        int fileCount,
        List<String> importantFiles,
        List<String> technologies,
        List<String> sampleFilePaths,
        boolean truncated
) {
}