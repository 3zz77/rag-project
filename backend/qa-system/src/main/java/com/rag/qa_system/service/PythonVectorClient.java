package com.rag.qa_system.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class PythonVectorClient {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${python-vector.base-url:http://127.0.0.1:5000}")
    private String baseUrl;

    public void assertAvailable() {
        String url = baseUrl + "/health";
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("Python向量服务不可用，健康检查失败，HTTP状态：" + response.getStatusCode());
            }
        } catch (ResourceAccessException e) {
            throw new IllegalStateException("无法连接Python向量服务，请先启动 d:\\RAG\\python-service\\app.py（默认端口5000）");
        }
    }

    public void addChunk(Long documentId, Integer chunkIndex, String text) {
        String url = baseUrl + "/index/add";
        String chunkId = documentId + ":" + chunkIndex;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "chunk_id", chunkId,
                "text", text == null ? "" : text
        );
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("调用Python向量服务 addChunk 失败，HTTP状态：" + response.getStatusCode());
            }
        } catch (ResourceAccessException e) {
            throw new IllegalStateException("无法连接Python向量服务，请先启动 d:\\RAG\\python-service\\app.py（默认端口5000）");
        }
    }

    public List<SearchHit> search(String query, int topK) {
        String url = baseUrl + "/search";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "query", query == null ? "" : query,
                "top_k", topK
        );
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("调用Python向量服务 search 失败，HTTP状态：" + response.getStatusCode());
            }
            return parseHits(response.getBody());
        } catch (ResourceAccessException e) {
            throw new IllegalStateException("无法连接Python向量服务，请先启动 d:\\RAG\\python-service\\app.py（默认端口5000）");
        }
    }

    private List<SearchHit> parseHits(String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode results = root.path("results");
            List<SearchHit> hits = new ArrayList<>();
            if (!results.isArray()) {
                return hits;
            }
            for (JsonNode node : results) {
                String chunkId = node.path("chunk_id").asText();
                double score = node.path("score").asDouble();
                hits.add(new SearchHit(chunkId, score));
            }
            return hits;
        } catch (Exception e) {
            throw new IllegalStateException("解析Python向量检索响应失败: " + e.getMessage(), e);
        }
    }

    public record SearchHit(String chunkId, double score) {}
}
