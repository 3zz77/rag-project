package com.rag.qa_system.service;

import com.rag.qa_system.mapper.DocumentChunkMapper;
import com.rag.qa_system.mapper.QaHistoryMapper;
import com.rag.qa_system.model.DocumentChunk;
import com.rag.qa_system.model.PageResult;
import com.rag.qa_system.model.QaHistory;
import com.rag.qa_system.model.RetrievalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.*;

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

    // 多轮对话缓存：conversationId -> 最近 N 条消息
    private final Map<String, List<Map<String, String>>> conversationStore = new ConcurrentHashMap<>();
    private static final int MAX_CONVERSATION_HISTORY = 10;

    public QaService(QaHistoryMapper qaHistoryMapper,
                     DocumentChunkMapper documentChunkMapper,
                     PythonVectorClient pythonVectorClient,
                     DeepSeekChatService deepSeekChatService) {
        this.qaHistoryMapper = qaHistoryMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.pythonVectorClient = pythonVectorClient;
        this.deepSeekChatService = deepSeekChatService;
    }

    @Value("${rag.rewrite-query.enabled:true}")
    private boolean rewriteQueryEnabled;

    public QaHistory ask(String question) {
        return ask(question, null);
    }

    public QaHistory ask(String question, String conversationId) {
        long start = System.currentTimeMillis();

        // Query 改写：用 LLM 优化检索关键词
        String rewrittenQuery = rewriteQuery(question, conversationId);
        String retrievalQuery = rewrittenQuery != null ? rewrittenQuery : question;

        List<RetrievalResult> topChunks = vectorRetrieve(retrievalQuery, topK);
        long retrievalMs = System.currentTimeMillis() - start;

        String context = buildContext(topChunks);
        String answer;
        if (topChunks.isEmpty()) {
            answer = "根据当前文档无法确定，建议先上传相关 API 文档后再提问。";
        } else {
            String prompt = buildRagPrompt(question, context, conversationId);
            answer = deepSeekChatService.chat(SYSTEM_PROMPT, prompt);
        }

        QaHistory history = new QaHistory();
        history.setQuestion(question);
        history.setAnswer(answer);
        history.setContext(context);
        history.setModel(chatModel);
        history.setConversationId(conversationId);
        qaHistoryMapper.insert(history);

        if (conversationId != null && !conversationId.isBlank()) {
            saveToConversation(conversationId, "user", question);
            saveToConversation(conversationId, "assistant", answer);
        }

        log.info("Q&A completed: query='{}' -> rewritten='{}', retrieval={}ms, chunks={}, answer_len={}",
                question, retrievalQuery, retrievalMs, topChunks.size(), answer.length());
        return history;
    }

    public SseEmitter askStream(String question) {
        return askStream(question, null);
    }

    public SseEmitter askStream(String question, String conversationId) {
        SseEmitter emitter = new SseEmitter(120_000L);

        // Query 改写
        String rewrittenQuery = rewriteQuery(question, conversationId);
        String retrievalQuery = rewrittenQuery != null ? rewrittenQuery : question;

        List<RetrievalResult> topChunks = vectorRetrieve(retrievalQuery, topK);
        String context = buildContext(topChunks);

        // 发送 conversationId 和改写后的 query
        if (conversationId != null && !conversationId.isBlank()) {
            try {
                emitter.send(SseEmitter.event().name("conversationId").data(conversationId));
            } catch (Exception ignored) {}
        }
        if (rewrittenQuery != null && !rewrittenQuery.equals(question)) {
            try {
                emitter.send(SseEmitter.event().name("rewrittenQuery").data(rewrittenQuery));
            } catch (Exception ignored) {}
        }

        executor.execute(() -> {
            try {
                emitter.send(SseEmitter.event().name("context").data(context));

                if (topChunks.isEmpty()) {
                    String fallback = "根据当前文档无法确定，建议先上传相关 API 文档后再提问。";
                    emitter.send(SseEmitter.event().name("token").data(fallback));
                    emitter.send(SseEmitter.event().name("done").data("complete"));
                    emitter.complete();
                    saveHistory(question, fallback, context, conversationId);
                    if (conversationId != null && !conversationId.isBlank()) {
                        saveToConversation(conversationId, "user", question);
                        saveToConversation(conversationId, "assistant", fallback);
                    }
                    return;
                }

                String prompt = buildRagPrompt(question, context, conversationId);
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
                            saveHistory(question, fullAnswer.toString(), context, conversationId);
                            if (conversationId != null && !conversationId.isBlank()) {
                                saveToConversation(conversationId, "user", question);
                                saveToConversation(conversationId, "assistant", fullAnswer.toString());
                            }
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

    private final ExecutorService executor = new ThreadPoolExecutor(
            2, 8, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            r -> new Thread(r, "qa-stream-" + System.currentTimeMillis() % 100000),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private void saveHistory(String question, String answer, String context, String conversationId) {
        QaHistory history = new QaHistory();
        history.setQuestion(question);
        history.setAnswer(answer);
        history.setContext(context);
        history.setModel(chatModel);
        history.setConversationId(conversationId);
        qaHistoryMapper.insert(history);
    }

    private void saveToConversation(String conversationId, String role, String content) {
        conversationStore.computeIfAbsent(conversationId, k ->
                Collections.synchronizedList(new ArrayList<>())
        ).add(Map.of("role", role, "content", content));

        // 限制历史长度
        List<Map<String, String>> hist = conversationStore.get(conversationId);
        if (hist != null && hist.size() > MAX_CONVERSATION_HISTORY * 2) {
            synchronized (hist) {
                while (hist.size() > MAX_CONVERSATION_HISTORY * 2) {
                    hist.remove(0);
                }
            }
        }
    }

    public void clearConversation(String conversationId) {
        conversationStore.remove(conversationId);
    }

    public PageResult<QaHistory> recent(int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        List<QaHistory> list = qaHistoryMapper.findRecentWithPage(offset, pageSize);
        long total = qaHistoryMapper.count();
        return new PageResult<>(list, total, page, pageSize);
    }

    private List<RetrievalResult> vectorRetrieve(String question, int topK) {
        List<PythonVectorClient.SearchHit> hits = pythonVectorClient.search(question, topK, true);
        List<RetrievalResult> scored = new ArrayList<>();

        for (PythonVectorClient.SearchHit hit : hits) {
            String chunkId = hit.chunkId();
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
            "你是一个专业的技术文档问答助手。你的任务是基于提供的文档内容，准确回答用户关于 API 文档的问题。\n\n" +
            "核心原则（必须遵守）：\n" +
            "1. 准确引用文档中的具体数值、参数名、字段名、错误码等技术细节，不要模糊概括\n" +
            "2. 用清晰的结构组织答案：先给出一句话核心结论，再分点列出关键细节\n" +
            "3. 如果文档中有表格/列表数据，尽量完整列出相关内容，不要遗漏\n" +
            "4. 只回答用户问的问题，不要输出与问题无关的文档内容\n" +
            "5. 如果文档内容有矛盾或不确定之处，明确指出\n" +
            "6. 如果文档内容不足以回答问题，直接说「根据当前文档无法确定」，不要猜测\n" +
            "7. 如果用户的问题是对上一轮回答的追问（如「详细解释」、「举个例子」），结合对话历史理解用户意图，但回答依然要基于文档内容\n\n" +
            "输出格式：\n" +
            "- 使用清晰简洁的中文，技术术语保持英文原名\n" +
            "- 段落之间空一行，列表项用短横线开头\n" +
            "- 涉及接口时列出 Method、URL、关键参数和响应字段\n" +
            "- 涉及具体数值/参数名时必须原样引用，不要改写";

    private String buildRagPrompt(String question, String context, String conversationId) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("以下是与用户问题相关的文档内容：\n\n").append(context).append("\n");

        // 多轮对话：附加上下文消息
        if (conversationId != null && !conversationId.isBlank()) {
            List<Map<String, String>> hist = conversationStore.get(conversationId);
            if (hist != null && !hist.isEmpty()) {
                prompt.append("以下是用户在当前会话中的对话历史，可用于理解追问意图：\n");
                for (Map<String, String> msg : hist) {
                    String roleLabel = "user".equals(msg.get("role")) ? "用户" : "助手";
                    prompt.append(roleLabel).append("：").append(msg.get("content")).append("\n");
                }
                prompt.append("\n");
            }
        }

        prompt.append("请根据以上文档内容回答用户问题。注意：提取与问题相关的信息，用自己的话总结归纳，不要直接复制大段原文。\n\n");
        prompt.append("用户问题：").append(question);
        return prompt.toString();
    }

    /**
     * Query 改写 — 用 LLM 优化检索查询，提升召回准确率
     * 处理：术语扩展、指代消解、问题转关键词
     */
    private String rewriteQuery(String question, String conversationId) {
        if (!rewriteQueryEnabled) {
            return question;
        }

        // 构造改写 prompt
        StringBuilder rewritePrompt = new StringBuilder();
        rewritePrompt.append("请将用户的问题改写为一个更利于文档检索的查询语句。\n");
        rewritePrompt.append("改写规则：\n");
        rewritePrompt.append("1. 将口语化表达替换为专业术语（如「怎么登录」→「API认证方式 OAuth Token」）\n");
        rewritePrompt.append("2. 补充同义词和相关关键词，用空格分隔\n");
        rewritePrompt.append("3. 如果是追问（如「详细解释一下」、「举个例子」），根据对话历史还原完整的查询意图\n");
        rewritePrompt.append("4. 只输出改写后的查询语句，不要输出任何解释或其他内容\n\n");

        // 附上对话历史（如果有的话）
        if (conversationId != null && !conversationId.isBlank()) {
            List<Map<String, String>> hist = conversationStore.get(conversationId);
            if (hist != null && !hist.isEmpty()) {
                rewritePrompt.append("对话历史：\n");
                for (Map<String, String> msg : hist) {
                    String roleLabel = "user".equals(msg.get("role")) ? "用户" : "助手";
                    rewritePrompt.append(roleLabel).append("：").append(msg.get("content")).append("\n");
                }
                rewritePrompt.append("\n");
            }
        }

        rewritePrompt.append("用户问题：").append(question);

        try {
            String rewritten = deepSeekChatService.chat(REWRITE_SYSTEM_PROMPT, rewritePrompt.toString());
            if (rewritten != null && !rewritten.isBlank() && rewritten.length() < 500) {
                log.info("Query rewritten: '{}' -> '{}'", question, rewritten);
                return rewritten.trim();
            }
        } catch (Exception e) {
            log.warn("Query rewrite failed, using original: {}", e.getMessage());
        }
        return question; // 改写失败则返回原始问题
    }

    private static final String REWRITE_SYSTEM_PROMPT =
            "你是一个专业的搜索查询优化助手。你的任务是将用户的问题改写为一个更利于文档检索的查询语句。" +
            "只输出改写后的查询文本，不要输出任何解释或其他内容。";
}
