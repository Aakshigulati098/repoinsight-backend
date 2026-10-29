package com.repoinsight.repository.service;

import com.repoinsight.repository.entity.RepositoryInfo;
import com.repoinsight.repository.repository.RepositoryInfoRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepositoryIndexServiceTest {

    @Test
    void chunkContentUsesModelCompatibleOverlappingChunks() {
        String content = "a".repeat(2000);

        var chunks = RepositoryIndexService.chunkContent(content);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).hasSize(800);
        assertThat(chunks.get(1)).hasSize(800);
        assertThat(chunks.get(2)).hasSize(640);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(800));
        assertThat(chunks.get(0).substring(680))
                .isEqualTo(chunks.get(1).substring(0, 120));
    }

    @Test
    void oversizedEmbeddingInputIsSplitAndRetried() {
        VectorStore vectorStore = mock(VectorStore.class);
        List<Document> storedDocuments = new ArrayList<>();
        doAnswer(invocation -> {
            List<Document> batch = invocation.getArgument(0);
            if (batch.stream().anyMatch(document -> document.getText().length() > 250)) {
                throw new IllegalStateException(
                        "input (570 tokens) is too large to process");
            }
            storedDocuments.addAll(batch);
            return null;
        }).when(vectorStore).add(anyList());
        RepositoryIndexService service = new RepositoryIndexService(
                mock(RepositoryInfoRepository.class),
                vectorStore,
                mock(JdbcTemplate.class));

        int storedCount = service.addDocumentsWithTokenLimitFallback(List.of(
                new Document("a".repeat(300), Map.of("chunkNumber", 0))));

        assertThat(storedCount).isEqualTo(2);
        assertThat(storedDocuments).hasSize(2);
        assertThat(storedDocuments)
                .allSatisfy(document -> assertThat(document.getText().length())
                        .isLessThanOrEqualTo(250));
        assertThat(storedDocuments)
                .allSatisfy(document -> assertThat(document.getMetadata())
                        .containsKey("subChunkNumber"));
    }

    @Test
    void nonTokenEmbeddingErrorsAreNotRetriedAsSmallerChunks() {
        VectorStore vectorStore = mock(VectorStore.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(vectorStore).add(anyList());
        RepositoryIndexService service = new RepositoryIndexService(
                mock(RepositoryInfoRepository.class),
                vectorStore,
                mock(JdbcTemplate.class));

        assertThatThrownBy(() -> service.addDocumentsWithTokenLimitFallback(
                List.of(new Document("some content", Map.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
    }

    private JsonNode fileNode(String path, long size) {
        JsonNode file = mock(JsonNode.class);
        JsonNode pathNode = mock(JsonNode.class);
        JsonNode sizeNode = mock(JsonNode.class);
        when(file.path("path")).thenReturn(pathNode);
        when(pathNode.asText()).thenReturn(path);
        when(file.path("size")).thenReturn(sizeNode);
        when(sizeNode.asLong(0)).thenReturn(size);
        return file;
    }

    @Test
    void chunkContentReturnsNoChunksForBlankContent() {
        assertThat(RepositoryIndexService.chunkContent(" \n\t")).isEmpty();
    }

    @Test
    void estimatedChunkCountAccountsForChunkOverlap() {
        assertThat(RepositoryIndexService.estimateChunkCount(0)).isZero();
        assertThat(RepositoryIndexService.estimateChunkCount(800)).isEqualTo(1);
        assertThat(RepositoryIndexService.estimateChunkCount(801)).isEqualTo(2);
        assertThat(RepositoryIndexService.estimateChunkCount(1480)).isEqualTo(2);
        assertThat(RepositoryIndexService.estimateChunkCount(1481)).isEqualTo(3);
    }

    @Test
    void quickIndexSelectsPrioritizedFilesAndRespectsChunkBudget() {
        var selected = RepositoryIndexService.selectQuickIndexFiles(
                List.of(
                        fileNode("src/main/java/z/Service.java", 5000),
                        fileNode("src/main/java/Application.java", 1800),
                        fileNode("pom.xml", 400),
                        fileNode("README.md", 500),
                        fileNode("src/main/java/a/Controller.java", 3200)),
                4);

        assertThat(selected)
                .extracting(item -> item.file().path("path").asText())
                .containsExactly(
                        "README.md",
                        "pom.xml",
                        "src/main/java/Application.java",
                        "src/main/java/a/Controller.java");
        assertThat(selected).extracting(
                RepositoryIndexService.SelectedIndexFile::chunkLimit)
                .containsExactly(1, 1, 1, 1);
    }

    @Test
    void quickIndexSpreadsAdditionalChunksAfterSelectingFiles() {
        var selected = RepositoryIndexService.selectQuickIndexFiles(
                List.of(fileNode("README.md", 7000)),
                3);

        assertThat(selected).hasSize(1);
        assertThat(selected.get(0).chunkLimit()).isEqualTo(3);
    }

    @Test
    void prepareIndexingClearsPreviousFailureBeforeQueueingRetry() {
        RepositoryInfoRepository repositoryInfoRepository =
                mock(RepositoryInfoRepository.class);
        RepositoryInfo repository = new RepositoryInfo();
        repository.setId(7L);
        repository.setIndexingStatus("FAILED");
        repository.setIndexingProgress(38);
        repository.setIndexingError("Previous attempt failed");
        when(repositoryInfoRepository.findById(7L))
                .thenReturn(Optional.of(repository));

        RepositoryIndexService service = new RepositoryIndexService(
                repositoryInfoRepository,
                mock(VectorStore.class),
                mock(JdbcTemplate.class));

        service.prepareIndexing(7L);

        assertThat(repository.getIndexingStatus()).isEqualTo("PENDING");
        assertThat(repository.getIndexingProgress()).isZero();
        assertThat(repository.getIndexingStage()).isEqualTo("Queued for indexing");
        assertThat(repository.getIndexingError()).isNull();
        verify(repositoryInfoRepository).save(repository);
    }

    @Test
    void markInterruptedIndexingJobsMakesPersistedActiveJobsRetryable() {
        RepositoryInfoRepository repositoryInfoRepository =
                mock(RepositoryInfoRepository.class);
        RepositoryInfo repository = new RepositoryInfo();
        repository.setId(7L);
        repository.setIndexingStatus("IN_PROGRESS");
        repository.setIndexingProgress(50);
        repository.setIndexedChunks(1088);
        repository.setIndexingTotalChunks(2000);
        when(repositoryInfoRepository.findAll()).thenReturn(List.of(repository));

        RepositoryIndexService service = new RepositoryIndexService(
                repositoryInfoRepository,
                mock(VectorStore.class),
                mock(JdbcTemplate.class));

        service.markInterruptedIndexingJobs();

        assertThat(repository.getIndexingStatus()).isEqualTo("FAILED");
        assertThat(repository.getIndexingStage()).isEqualTo("Interrupted");
        assertThat(repository.getIndexingError())
                .contains("backend stopped");
        verify(repositoryInfoRepository).save(repository);
    }

    @Test
    void isIndexableKeepsProductionAndDocumentationButSkipsTestsAndGeneratedFiles() {
        RepositoryIndexService service = new RepositoryIndexService(
                mock(RepositoryInfoRepository.class),
                mock(VectorStore.class),
                mock(JdbcTemplate.class));

        assertThat(service.isIndexable("src/main/java/App.java", 100)).isTrue();
        assertThat(service.isIndexable("docs/guide.md", 100)).isTrue();
        assertThat(service.isIndexable("src/test/java/AppTest.java", 100)).isFalse();
        assertThat(service.isIndexable("examples/demo/App.java", 100)).isFalse();
        assertThat(service.isIndexable("target/generated/App.java", 100)).isFalse();
        assertThat(service.isIndexable("vendor/library/App.java", 100)).isFalse();
    }
}
