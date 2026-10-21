package com.repoinsight.repository.controller;

import com.repoinsight.repository.service.RepositoryChatService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/repositories")
public class RepositoryIndexingController {

    private final RepositoryChatService chatService;

    public RepositoryIndexingController(
            RepositoryChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/{id}/chat")
    public Map<String, Object> chat(
            @PathVariable("id") Long id,
            @RequestBody Map<String, String> request) {

        String question = request.get("question");

        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException(
                    "Question is required");
        }

        return chatService.ask(id, question);
    }
}