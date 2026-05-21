package com.rag.qa_system.controller;

import com.rag.qa_system.service.ZhipuChatService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/llm")
@Validated
public class LlmDebugController {

    private final ZhipuChatService zhipuChatService;

    public LlmDebugController(ZhipuChatService zhipuChatService) {
        this.zhipuChatService = zhipuChatService;
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody @Validated ChatRequest request) {
        String answer = zhipuChatService.chat(request.message());
        return Map.of(
                "model", "zhipu-chat",
                "question", request.message(),
                "answer", answer
        );
    }

    public record ChatRequest(@NotBlank(message = "message不能为空") String message) {}
}
