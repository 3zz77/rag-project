package com.rag.qa_system.service;

import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mock 向量服务：用于教学和本地联调。
 * 注意：这不是真正语义向量，只是可复现的占位实现。
 */
@Service
@Profile("mock-embedding")
public class MockEmbeddingService implements EmbeddingService {

    private static final int DIM = 16;

    @Override
    public List<Float> embed(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        float[] vec = new float[DIM];
        for (int i = 0; i < normalized.length(); i++) {
            int idx = i % DIM;
            vec[idx] += normalized.charAt(i);
        }

        // L2 归一化，避免长度差异影响太大
        double norm = 0.0;
        for (float v : vec) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vec.length; i++) {
                vec[i] = (float) (vec[i] / norm);
            }
        }

        List<Float> result = new ArrayList<>(DIM);
        for (float v : vec) {
            result.add(v);
        }
        return result;
    }
}
