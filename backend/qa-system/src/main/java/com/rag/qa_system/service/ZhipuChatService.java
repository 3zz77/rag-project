package com.rag.qa_system.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
public class ZhipuChatService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${zhipu.api-key}")
    private String apiKey;

    @Value("${zhipu.base-url}")
    private String baseUrl;

    @Value("${zhipu.chat-model}")
    private String chatModel;

    public String chat(String userMessage) {
        String url = baseUrl + "/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = Map.of(
                "model", chatModel,
                "messages", List.of(
                        Map.of("role", "user", "content", userMessage)
                ),
                "temperature", 0.3
        );

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("调用智谱聊天接口失败，HTTP状态：" + response.getStatusCode());
        }

        return parseAssistantContent(response.getBody());
    }

    private String parseAssistantContent(String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            if (contentNode.isMissingNode() || contentNode.asText().isBlank()) {
                throw new IllegalStateException("智谱响应中未找到 message.content");
            }
            return contentNode.asText();
        } catch (Exception e) {
            throw new IllegalStateException("解析智谱响应失败: " + e.getMessage(), e);
        }
    }
}
