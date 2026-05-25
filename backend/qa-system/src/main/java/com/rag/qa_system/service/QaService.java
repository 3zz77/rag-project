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
            return "未命中任何文档分块。";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < topChunks.size(); i++) {
            RetrievalResult item = topChunks.get(i);
            builder.append("片段").append(i + 1)
                    .append("（similarity=").append(String.format("%.4f", item.score())).append("）: ")
                    .append(item.chunk().getContent());
            if (i < topChunks.size() - 1) {
                builder.append("\n---\n");
            }
        }
        return builder.toString();
    }

    private static final String SYSTEM_PROMPT =
            "你是一个专业的企业级 API 文档问答助手。你的回答必须严格基于提供的检索上下文，不得编造或使用外部知识。\n\n" +
            "回答格式要求：\n" +
            "1. 用纯文本回答，不要使用 Markdown 符号（不要用 ##、**、`、|表格| 等格式）\n" +
            "2. 每个要点之间空一行，形成清晰的段落分隔\n" +
            "3. 先给出一句话的核心结论，然后空一行再展开\n" +
            "4. 列表项用数字编号或短横线开头，每项一行\n" +
            "5. 涉及接口调用时，分行写出 Method、URL、请求参数、响应格式\n" +
            "6. 技术术语保持英文，其余用简洁中文\n" +
            "7. 如果上下文不足以回答问题，明确说「根据当前文档无法确定」\n" +
            "8. 不要标注来源编号或片段编号\n\n" +
            "输出示例：\n" +
            "该接口用于获取用户的基本信息，需要先获取 access_token 才能调用。\n" +
            "\n" +
            "请求方式：GET\n" +
            "接口地址：/api/user/info\n" +
            "\n" +
            "请求参数：\n" +
            "- userId：字符串，必填，用户的唯一标识\n" +
            "- fields：字符串，可选，指定返回的字段列表，多个用逗号分隔\n" +
            "\n" +
            "响应格式（JSON）：\n" +
            "{\n" +
            "  \"code\": 0,\n" +
            "  \"data\": {\n" +
            "    \"name\": \"张三\",\n" +
            "    \"avatar\": \"https://example.com/avatar.jpg\"\n" +
            "  }\n" +
            "}\n" +
            "\n" +
            "注意事项：\n" +
            "- access_token 有效期为 2 小时，过期需重新获取\n" +
            "- 接口调用频率限制为每分钟 60 次";

    private String buildRagPrompt(String question, String context) {
        return "[检索上下文]\n" + context + "\n\n" +
                "[用户问题]\n" + question;
    }
}
