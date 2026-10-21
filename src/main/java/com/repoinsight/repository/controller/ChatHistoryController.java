package com.repoinsight.repository.controller;

import com.repoinsight.repository.service.ChatHistoryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chats")
public class ChatHistoryController {

    private final ChatHistoryService chatHistoryService;

    public ChatHistoryController(ChatHistoryService chatHistoryService) {
        this.chatHistoryService = chatHistoryService;
    }

    @GetMapping("/{chatId}/messages")
    public List<Map<String, Object>> getMessages(@PathVariable Long chatId) {
        return chatHistoryService.getMessages(chatId);
    }

    @PostMapping("/{chatId}/messages")
    public Map<String, Object> sendMessage(
            @PathVariable Long chatId,
            @RequestBody Map<String, String> request) {
        return chatHistoryService.sendMessage(chatId, request.get("question"));
    }
}
