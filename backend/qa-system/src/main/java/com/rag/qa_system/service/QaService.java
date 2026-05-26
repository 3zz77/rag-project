package com.rag.qa_system.service;

import com.rag.qa_system.mapper.DocumentChunkMapper;
import com.rag.qa_system.mapper.QaHistoryMapper;
import com.rag.qa_system.model.DocumentChunk;
import com.rag.qa_system.model.QaHistory;
import com.rag.qa_system.model.RetrievalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class QaService {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);

    private final QaHistoryMapper qaHistoryMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final PythonVectorClient pythonVectorClient;
    private final DeepSeekChatService deepSeekChatService;

    @Value("${rag.retrieval.top-k:3}")
    private int topK;

    @Value("${deepseek.chat-model}")
    private String chatModel;

    public QaService(QaHistoryMapper qaHistoryMapper,
                     DocumentChunkMapper documentChunkMapper,
                     PythonVectorClient pythonVectorClient,
                     DeepSeekChatService deepSeekChatService) {
        this.qaHistoryMapper = qaHistoryMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.pythonVectorClient = pythonVectorClient;
        this.deepSeekChatService = deepSeekChatService;
    }

    public QaHistory ask(String question) {
        long start = System.currentTimeMillis();
        List<RetrievalResult> topChunks = vectorRetrieve(question, topK);
        long retrievalMs = System.currentTimeMillis() - start;

        String context = buildContext(topChunks);
        String answer;
        if (topChunks.isEmpty()) {
            answer = "根据当前文档无法确定，建议先上传相关 API 文档后再提问。";
        } else {
            String prompt = buildRagPrompt(question, context);
            answer = deepSeekChatService.chat(SYSTEM_PROMPT, prompt);
        }

        QaHistory history = new QaHistory();
        history.setQuestion(question);
        history.setAnswer(answer);
        history.setContext(context);
        history.setModel(chatModel);
        qaHistoryMapper.insert(history);
        log.info("Q&A completed: retrieval={}ms, chunks={}, question_len={}, answer_len={}",
                retrievalMs, topChunks.size(), question.length(), answer.length());
        return history;
    }

    public SseEmitter askStream(String question) {
        SseEmitter emitter = new SseEmitter(120_000L);
        List<RetrievalResult> topChunks = vectorRetrieve(question, topK);
        String context = buildContext(topChunks);

        executor.execute(() -> {
            try {
                emitter.send(SseEmitter.event().name("context").data(context));

                if (topChunks.isEmpty()) {
                    emitter.send(SseEmitter.event().name("token").data("根据当前文档无法确定，建议先上传相关 API 文档后再提问。"));
                    emitter.send(SseEmitter.event().name("done").data("complete"));
                    emitter.complete();
                    saveHistory(question, "根据当前文档无法确定，建议先上传相关 API 文档后再提问。", context);
                    return;
                }

                String prompt = buildRagPrompt(question, context);
                StringBuilder fullAnswer = new StringBuilder();

                deepSeekChatService.chatStream(SYSTEM_PROMPT, prompt,
                        token -> {
                            fullAnswer.append(token);
                            try {
                                emitter.send(SseEmitter.event().name("token").data(token));
                            } catch (Exception e) {
                                log.warn("SSE发送token失败", e);
                            }
                        },
                        error -> {
                            log.error("流式调用失败", error);
                            try {
                                emitter.send(SseEmitter.event().name("error").data(error.getMessage()));
                            } catch (Exception ignored) {}
                            emitter.completeWithError(error);
                        },
                        () -> {
                            saveHistory(question, fullAnswer.toString(), context);
                            try {
                                emitter.send(SseEmitter.event().name("done").data("complete"));
                                emitter.complete();
                            } catch (Exception e) {
                                log.warn("SSE完成事件发送失败", e);
                            }
                        }
                );
            } catch (Exception e) {
                log.error("SSE流式问答失败", e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private final java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newCachedThreadPool(
                    r -> new Thread(r, "qa-stream-" + System.currentTimeMillis() % 100000)
            );

    private void saveHistory(String question, String answer, String context) {
        QaHistory history = new QaHistory();
        history.setQuestion(question);
        history.setAnswer(answer);
        history.setContext(context);
        history.setModel(chatModel);
        qaHistoryMapper.insert(history);
    }

    public List<QaHistory> recent(int limit) {
        return qaHistoryMapper.findRecent(limit);
    }

    private List<RetrievalResult> vectorRetrieve(String question, int topK) {
        List<PythonVectorClient.SearchHit> hits = pythonVectorClient.search(question, topK, true);
        List<RetrievalResult> scored = new ArrayList<>();

        for (PythonVectorClient.SearchHit hit : hits) {
            String chunkId = hit.chunkId(); // format: documentId:chunkIndex
            String[] parts = chunkId.split(":");
            if (parts.length != 2) {
                continue;
            }
            Long documentId = Long.parseLong(parts[0]);
            Integer chunkIndex = Integer.parseInt(parts[1]);
            DocumentChunk chunk = documentChunkMapper.findByDocIdAndChunkIndex(documentId, chunkIndex);
            if (chunk != null && chunk.getContent() != null && !chunk.getContent().isBlank()) {
                scored.add(new RetrievalResult(chunk, hit.score()));
            }
        }

        Map<String, RetrievalResult> uniqueByContent = new LinkedHashMap<>();
        for (RetrievalResult item : scored) {
            String key = item.chunk().getContent().trim();
            RetrievalResult existing = uniqueByContent.get(key);
            if (existing == null || item.score() > existing.score()) {
                uniqueByContent.put(key, item);
            }
        }

        scored = new ArrayList<>(uniqueByContent.values());
        scored.sort(Comparator.comparingDouble(RetrievalResult::score).reversed());
        if (scored.size() > topK) {
            return scored.subList(0, topK);
        }
        return scored;
    }

    private String buildContext(List<RetrievalResult> topChunks) {
        if (topChunks.isEmpty()) {
            return "未命中任何文档内容。";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < topChunks.size(); i++) {
            RetrievalResult item = topChunks.get(i);
            builder.append("---\n").append(item.chunk().getContent()).append("\n");
        }
        return builder.toString();
    }

    private static final String SYSTEM_PROMPT =
            "你是一个专业的技术文档问答助手。你的任务是基于提供的文档内容，用自己的话简洁准确地回答用户问题。\n\n" +
            "核心原则（必须遵守）：\n" +
            "1. 总结归纳，严禁大段复制原文。从文档中提取关键信息，用自己的语言重新组织后回答\n" +
            "2. 只回答用户问的问题，不要输出与问题无关的文档内容，不要做多余的延伸\n" +
            "3. 先给出一句话核心结论，再展开说明细节，保持简洁\n" +
            "4. 如果文档内容有矛盾或不确定之处，明确指出\n" +
            "5. 如果文档内容不足以回答问题，直接说「根据当前文档无法确定」，不要猜测\n\n" +
            "输出格式：\n" +
            "- 使用清晰简洁的中文，技术术语保持英文\n" +
            "- 段落之间空一行，列表项用短横线开头\n" +
            "- 涉及接口时列出 Method、URL、关键参数和响应字段即可，不需要完整 JSON 示例\n" +
            "- 不要标注来源编号、片段编号或页码";

    private String buildRagPrompt(String question, String context) {
        return "以下是与用户问题相关的文档内容：\n\n" + context + "\n" +
                "请根据以上文档内容回答用户问题。注意：提取与问题相关的信息，用自己的话总结归纳，不要直接复制大段原文。\n\n" +
                "用户问题：" + question;
    }
}
