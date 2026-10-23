package com.repoinsight.repository.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.LocalDateTime;

@Entity
public class RepositoryInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String githubUrl;
    private String description;
    private Integer stars;
    private String language;
    private String defaultBranch;
    private String indexingStatus = "NOT_INDEXED";
    private Integer indexingProgress = 0;
    private Integer indexedChunks = 0;
    private LocalDateTime indexingStartedAt;
    private LocalDateTime indexingCompletedAt;
    private String indexingError;

    public Integer getStars() {
        return stars;
    }

    public void setStars(Integer stars) {
        this.stars = stars;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    public RepositoryInfo() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getGithubUrl() {
        return githubUrl;
    }

    public void setGithubUrl(String githubUrl) {
        this.githubUrl = githubUrl;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getIndexingStatus() {
        return indexingStatus;
    }

    public void setIndexingStatus(String indexingStatus) {
        this.indexingStatus = indexingStatus;
    }

    public Integer getIndexingProgress() {
        return indexingProgress;
    }

    public void setIndexingProgress(Integer indexingProgress) {
        this.indexingProgress = indexingProgress;
    }

    public Integer getIndexedChunks() {
        return indexedChunks;
    }

    public void setIndexedChunks(Integer indexedChunks) {
        this.indexedChunks = indexedChunks;
    }

    public LocalDateTime getIndexingStartedAt() {
        return indexingStartedAt;
    }

    public void setIndexingStartedAt(LocalDateTime indexingStartedAt) {
        this.indexingStartedAt = indexingStartedAt;
    }

    public LocalDateTime getIndexingCompletedAt() {
        return indexingCompletedAt;
    }

    public void setIndexingCompletedAt(LocalDateTime indexingCompletedAt) {
        this.indexingCompletedAt = indexingCompletedAt;
    }

    public String getIndexingError() {
        return indexingError;
    }

    public void setIndexingError(String indexingError) {
        this.indexingError = indexingError;
    }
}
