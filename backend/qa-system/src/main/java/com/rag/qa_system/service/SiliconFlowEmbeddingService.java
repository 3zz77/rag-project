package com.rag.qa_system.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Profile("siliconflow-embedding")
public class SiliconFlowEmbeddingService implements EmbeddingService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${siliconflow.api-key}")
    private String apiKey;

    @Value("${siliconflow.base-url}")
    private String baseUrl;

    @Value("${siliconflow.embedding-model}")
    private String embeddingModel;

    public SiliconFlowEmbeddingService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public List<Float> embed(String text) {
        String url = baseUrl + "/embeddings";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = Map.of(
                "model", embeddingModel,
                "input", text == null ? "" : text
        );

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("调用硅基流动 embedding 接口失败，HTTP状态：" + response.getStatusCode());
        }
        return parseEmbedding(response.getBody());
    }

    private List<Float> parseEmbedding(String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode array = root.path("data").path(0).path("embedding");
            if (!array.isArray() || array.isEmpty()) {
                throw new IllegalStateException("硅基流动 embedding 响应格式异常");
            }
            List<Float> result = new ArrayList<>(array.size());
            for (JsonNode node : array) {
                result.add((float) node.asDouble());
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("解析硅基流动 embedding 响应失败: " + e.getMessage(), e);
        }
    }
}
