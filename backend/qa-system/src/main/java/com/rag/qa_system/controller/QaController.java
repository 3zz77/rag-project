package com.rag.qa_system.controller;

import com.rag.qa_system.model.ApiResponse;
import com.rag.qa_system.model.PageResult;
import com.rag.qa_system.model.QaHistory;
import com.rag.qa_system.service.QaService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/qa")
@Validated
public class QaController {

    private final QaService qaService;

    public QaController(QaService qaService) {
        this.qaService = qaService;
    }

    @PostMapping("/ask")
    public ApiResponse<Map<String, Object>> ask(@RequestBody @Validated AskRequest request) {
        QaHistory history = qaService.ask(request.question(), request.conversationId());
        Map<String, Object> data = new HashMap<>();
        data.put("id", history.getId());
        data.put("question", history.getQuestion());
        data.put("answer", history.getAnswer());
        data.put("context", history.getContext());
        data.put("model", history.getModel());
        if (history.getConversationId() != null) {
            data.put("conversationId", history.getConversationId());
        }
        return ApiResponse.success(data);
    }

    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestBody @Validated AskRequest request) {
        return qaService.askStream(request.question(), request.conversationId());
    }

    @GetMapping("/history")
    public ApiResponse<PageResult<QaHistory>> history(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) int pageSize) {
        return ApiResponse.success(qaService.recent(page, pageSize));
    }

    @DeleteMapping("/history/conversation/{conversationId}")
    public ApiResponse<?> clearConversation(@PathVariable String conversationId) {
        qaService.clearConversation(conversationId);
        return ApiResponse.success("会话已清除");
    }

    public record AskRequest(
            @NotBlank(message = "question不能为空") String question,
            String conversationId
    ) {}
}
