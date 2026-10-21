package com.repoinsight.repository.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiSummaryServiceTest {

    @Test
    void generateSummary_ReturnsModelResponse() {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);

        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("Generated summary");

        AiSummaryService service = new AiSummaryService(builder);

        String summary = service.generateSummary(
                "demo-repo",
                "Demo repository",
                "# Demo README",
                "Java",
                "pom.xml");

        assertThat(summary).isEqualTo("Generated summary");
    }

    @Test
    void generateArchitectureDiagram_StripsMarkdownFences() {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);

        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("```mermaid\nflowchart TD\nA[Application]\n``` ");

        AiSummaryService service = new AiSummaryService(builder);

        String diagram = service.generateArchitectureDiagram(
                "demo-repo",
                "Demo repository",
                "# Demo README",
                "Java",
                "pom.xml");

        assertThat(diagram).startsWith("flowchart TD");
        assertThat(diagram).contains("A[Application]");
    }

    @Test
    void generateArchitectureDiagram_AcceptsOtherDirectionsAndIntroductoryText() {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);

        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(
                "Here is the diagram:\n```mermaid\nflowchart TB\nA[Application]\n```");

        AiSummaryService service = new AiSummaryService(builder);

        String diagram = service.generateArchitectureDiagram(
                "demo-repo",
                "Demo repository",
                "# Demo README",
                "Java",
                "pom.xml");

        assertThat(diagram).startsWith("flowchart TB");
        assertThat(diagram).contains("A[Application]");
    }

    @Test
    void generateSummary_WhenGeminiFails_PropagatesExternalServiceFailure() {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);

        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenThrow(new IllegalStateException("Gemini unavailable"));

        AiSummaryService service = new AiSummaryService(builder);

        assertThatThrownBy(() -> service.generateSummary(
                "demo-repo", null, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Gemini unavailable");
    }

    @Test
    void generateArchitectureDiagram_EmptyGeminiResponse_ThrowsException() {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);

        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("  ");

        AiSummaryService service = new AiSummaryService(builder);

        assertThatThrownBy(() -> service.generateArchitectureDiagram(
                "demo-repo", null, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty architecture diagram");
    }

    @Test
    void generateArchitectureDiagram_InvalidGeminiResponse_ThrowsException() {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);

        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("not a Mermaid diagram");

        AiSummaryService service = new AiSummaryService(builder);

        assertThatThrownBy(() -> service.generateArchitectureDiagram(
                "demo-repo", null, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid Mermaid diagram");
    }
}
