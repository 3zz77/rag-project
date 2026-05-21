package com.rag.qa_system.model;

/**
 * 检索结果：某个分块及其相似度分数
 */
public record RetrievalResult(DocumentChunk chunk, double score) {
}
