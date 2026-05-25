package com.rag.qa_system.controller;

import com.rag.qa_system.service.DeepSeekChatService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/llm")
@Validated
public class LlmDebugController {

    private final DeepSeekChatService deepSeekChatService;

    public LlmDebugController(DeepSeekChatService deepSeekChatService) {
        this.deepSeekChatService = deepSeekChatService;
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody @Validated ChatRequest request) {
        String answer = deepSeekChatService.chat(request.message());
        return Map.of(
                "model", "deepseek-chat",
                "question", request.message(),
                "answer", answer
        );
    }

    public record ChatRequest(@NotBlank(message = "message不能为空") String message) {}
}
