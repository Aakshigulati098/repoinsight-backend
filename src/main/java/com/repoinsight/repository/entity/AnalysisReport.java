package com.repoinsight.repository.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "analysis_reports")
public class AnalysisReport {

    @Id
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id", nullable = false)
    private RepositoryInfo repositoryInfo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AnalysisStatus status;

    @Column(columnDefinition = "TEXT")
    private String readmeContent;

    private Integer fileCount;

    private LocalDateTime createdAt;

    @Column(columnDefinition = "TEXT")
    private String importantFiles;

    @Column(columnDefinition = "TEXT")
    private String technologies;

    @Column(columnDefinition = "TEXT")
    private String sampleFilePaths;

    private Boolean treeTruncated;

    @Column(columnDefinition = "TEXT")
    private String aiSummary;

    @Column(columnDefinition = "TEXT")
    private String architectureDiagram;

    public AnalysisReport() {
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public RepositoryInfo getRepositoryInfo() {
        return repositoryInfo;
    }

    public void setRepositoryInfo(RepositoryInfo repositoryInfo) {
        this.repositoryInfo = repositoryInfo;
    }

    public AnalysisStatus getStatus() {
        return status;
    }

    public void setStatus(AnalysisStatus status) {
        this.status = status;
    }

    public String getReadmeContent() {
        return readmeContent;
    }

    public void setReadmeContent(String readmeContent) {
        this.readmeContent = readmeContent;
    }

    public Integer getFileCount() {
        return fileCount;
    }

    public void setFileCount(Integer fileCount) {
        this.fileCount = fileCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    public String getImportantFiles() {
        return importantFiles;
    }

    public void setImportantFiles(String importantFiles) {
        this.importantFiles = importantFiles;
    }

    public String getTechnologies() {
        return technologies;
    }

    public void setTechnologies(String technologies) {
        this.technologies = technologies;
    }

    public String getSampleFilePaths() {
        return sampleFilePaths;
    }

    public void setSampleFilePaths(String sampleFilePaths) {
        this.sampleFilePaths = sampleFilePaths;
    }

    public Boolean getTreeTruncated() {
        return treeTruncated;
    }

    public void setTreeTruncated(Boolean treeTruncated) {
        this.treeTruncated = treeTruncated;
    }

    public String getAiSummary() {
        return aiSummary;
    }

    public void setAiSummary(String aiSummary) {
        this.aiSummary = aiSummary;
    }

    public String getArchitectureDiagram() {
        return architectureDiagram;
    }

    public void setArchitectureDiagram(String architectureDiagram) {
        this.architectureDiagram = architectureDiagram;
    }
}