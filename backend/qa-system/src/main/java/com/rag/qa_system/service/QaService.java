package com.rag.qa_system.service;

import com.rag.qa_system.mapper.DocumentChunkMapper;
import com.rag.qa_system.mapper.QaHistoryMapper;
import com.rag.qa_system.model.DocumentChunk;
import com.rag.qa_system.model.QaHistory;
import com.rag.qa_system.model.RetrievalResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class QaService {

    private final QaHistoryMapper qaHistoryMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final PythonVectorClient pythonVectorClient;
    private final ZhipuChatService zhipuChatService;

    @Value("${rag.retrieval.top-k:3}")
    private int topK;

    @Value("${zhipu.chat-model}")
    private String chatModel;

    public QaService(QaHistoryMapper qaHistoryMapper,
                     DocumentChunkMapper documentChunkMapper,
                     PythonVectorClient pythonVectorClient,
                     ZhipuChatService zhipuChatService) {
        this.qaHistoryMapper = qaHistoryMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.pythonVectorClient = pythonVectorClient;
        this.zhipuChatService = zhipuChatService;
    }

    public QaHistory ask(String question) {
        List<DocumentChunk> chunks = documentChunkMapper.findAll();
        List<RetrievalResult> topChunks = vectorRetrieve(question, chunks, topK);

        String context = buildContext(topChunks);
        String answer;
        if (topChunks.isEmpty()) {
            answer = "我没有在已上传文档中找到与你问题相关的内容。请先上传相关文档，或换个问法。";
        } else {
            String prompt = buildRagPrompt(question, context);
            answer = zhipuChatService.chat(prompt);
        }

        QaHistory history = new QaHistory();
        history.setQuestion(question);
        history.setAnswer(answer);
        history.setContext(context);
        history.setModel(chatModel);
        qaHistoryMapper.insert(history);
        return history;
    }

    public List<QaHistory> recent(int limit) {
        return qaHistoryMapper.findRecent(limit);
    }

    private List<RetrievalResult> vectorRetrieve(String question, List<DocumentChunk> chunks, int topK) {
        List<PythonVectorClient.SearchHit> hits = pythonVectorClient.search(question, topK);
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

        // 按内容去重：同样 content 只保留得分最高的一条
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

    private String buildRagPrompt(String question, String context) {
        return "你是一个API文档问答助手。请严格根据“检索上下文”回答问题，不要编造。\n" +
                "如果上下文不足，请明确说“根据当前文档无法确定”。\n\n" +
                "【检索上下文】\n" + context + "\n\n" +
                "【用户问题】\n" + question + "\n\n" +
                "请给出简洁、准确的回答。";
    }
}
