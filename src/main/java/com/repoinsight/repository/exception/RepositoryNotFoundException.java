package com.repoinsight.repository.exception;

public class RepositoryNotFoundException extends RuntimeException {

    public RepositoryNotFoundException(Long id) {
        super("Repository not found with id: " + id);
    }
}
