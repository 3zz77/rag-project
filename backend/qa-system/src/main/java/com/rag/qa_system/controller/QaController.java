package com.rag.qa_system.controller;

import com.rag.qa_system.model.QaHistory;
import com.rag.qa_system.service.QaService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
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
    public Map<String, Object> ask(@RequestBody @Validated AskRequest request) {
        QaHistory history = qaService.ask(request.question());
        return Map.of(
                "id", history.getId(),
                "question", history.getQuestion(),
                "answer", history.getAnswer(),
                "context", history.getContext(),
                "model", history.getModel()
        );
    }

    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestBody @Validated AskRequest request) {
        return qaService.askStream(request.question());
    }

    @GetMapping("/history")
    public List<QaHistory> history(@RequestParam(defaultValue = "10") @Min(1) int limit) {
        return qaService.recent(limit);
    }

    public record AskRequest(@NotBlank(message = "question不能为空") String question) {}
}
