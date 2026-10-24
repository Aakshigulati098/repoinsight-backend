package com.repoinsight.repository.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.LocalDateTime;

@Entity
public class RepositoryInfo {

    @Id
    private Long id;

    private String name;
    private String githubUrl;
    private String description;
    private Integer stars;
    private String language;
    private String defaultBranch;
    private String ownerName;
    private String ownerAvatarUrl;
    private String ownerUrl;
    private Integer forks = 0;
    private Integer openIssues = 0;
    private String license;
    private String topics;
    private String indexingStatus = "NOT_INDEXED";
    private String indexingStage;
    private Integer indexingProgress = 0;
    private Integer indexedChunks = 0;
    private Integer indexingTotalChunks = 0;
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

    public void setId(Long id) {
        this.id = id;
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

    public String getIndexingStage() {
        return indexingStage;
    }

    public void setIndexingStage(String indexingStage) {
        this.indexingStage = indexingStage;
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

    public Integer getIndexingTotalChunks() {
        return indexingTotalChunks;
    }

    public void setIndexingTotalChunks(Integer indexingTotalChunks) {
        this.indexingTotalChunks = indexingTotalChunks;
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

    public String getOwnerName() {
        if (ownerName != null && !ownerName.isBlank()) {
            return ownerName;
        }
        if (githubUrl != null && githubUrl.contains("github.com/")) {
            String path = githubUrl.replaceFirst("^https?://[^/]+/", "");
            String[] parts = path.split("/");
            if (parts.length > 0 && !parts[0].isBlank()) {
                return parts[0];
            }
        }
        return null;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getOwnerAvatarUrl() {
        if (ownerAvatarUrl != null && !ownerAvatarUrl.isBlank()) {
            return ownerAvatarUrl;
        }
        String owner = getOwnerName();
        if (owner != null && !owner.isBlank()) {
            return "https://github.com/" + owner + ".png";
        }
        return null;
    }

    public void setOwnerAvatarUrl(String ownerAvatarUrl) {
        this.ownerAvatarUrl = ownerAvatarUrl;
    }

    public String getOwnerUrl() {
        if (ownerUrl != null && !ownerUrl.isBlank()) {
            return ownerUrl;
        }
        String owner = getOwnerName();
        if (owner != null && !owner.isBlank()) {
            return "https://github.com/" + owner;
        }
        return null;
    }

    public void setOwnerUrl(String ownerUrl) {
        this.ownerUrl = ownerUrl;
    }

    public Integer getForks() {
        return forks != null ? forks : 0;
    }

    public void setForks(Integer forks) {
        this.forks = forks;
    }

    public Integer getOpenIssues() {
        return openIssues != null ? openIssues : 0;
    }

    public void setOpenIssues(Integer openIssues) {
        this.openIssues = openIssues;
    }

    public String getLicense() {
        return license;
    }

    public void setLicense(String license) {
        this.license = license;
    }

    public String getTopics() {
        return topics;
    }

    public void setTopics(String topics) {
        this.topics = topics;
    }
}
