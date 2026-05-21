package com.rag.qa_system.service;

import java.util.List;

/**
 * 向量化服务接口
 * 通过不同实现切换 mock / 真实 embedding API。
 */
public interface EmbeddingService {

    /**
     * 将文本转成向量
     */
    List<Float> embed(String text);
}
