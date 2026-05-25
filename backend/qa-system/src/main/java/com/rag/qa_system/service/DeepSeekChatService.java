package com.rag.qa_system.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class DeepSeekChatService {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekChatService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${deepseek.api-key}")
    private String apiKey;

    @Value("${deepseek.base-url}")
    private String baseUrl;

    @Value("${deepseek.chat-model}")
    private String chatModel;

    public DeepSeekChatService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public String chat(String userMessage) {
        return chat(null, userMessage);
    }

    public String chat(String systemMessage, String userMessage) {
        String url = baseUrl + "/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        List<Map<String, String>> messages = new ArrayList<>();
        if (systemMessage != null && !systemMessage.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemMessage));
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> body = Map.of(
                "model", chatModel,
                "messages", messages,
                "temperature", 0.3
        );

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("调用DeepSeek聊天接口失败，HTTP状态：" + response.getStatusCode());
        }

        return parseAssistantContent(response.getBody());
    }

    public void chatStream(String systemMessage, String userMessage,
                           Consumer<String> onToken, Consumer<Exception> onError, Runnable onComplete) {
        new Thread(() -> {
            try {
                String url = baseUrl + "/chat/completions";

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(apiKey);

                List<Map<String, String>> messages = new ArrayList<>();
                if (systemMessage != null && !systemMessage.isBlank()) {
                    messages.add(Map.of("role", "system", "content", systemMessage));
                }
                messages.add(Map.of("role", "user", "content", userMessage));

                Map<String, Object> body = Map.of(
                        "model", chatModel,
                        "messages", messages,
                        "temperature", 0.3,
                        "stream", true
                );

                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

                restTemplate.execute(url, HttpMethod.POST, request -> {
                    headers.forEach((key, values) ->
                            values.forEach(value -> request.getHeaders().add(key, value)));
                    request.getBody().write(objectMapper.writeValueAsBytes(body));
                }, response -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("data: ")) {
                                String data = line.substring(6);
                                if ("[DONE]".equals(data)) {
                                    break;
                                }
                                try {
                                    JsonNode node = objectMapper.readTree(data);
                                    JsonNode choices = node.path("choices");
                                    if (choices.isArray() && choices.size() > 0) {
                                        JsonNode delta = choices.get(0).path("delta");
                                        JsonNode content = delta.path("content");
                                        if (!content.isMissingNode() && !content.isNull() && !content.asText().isEmpty()) {
                                            onToken.accept(content.asText());
                                        }
                                    }
                                } catch (Exception e) {
                                    log.debug("解析流式响应行失败: {}", data, e);
                                }
                            }
                        }
                    }
                    onComplete.run();
                    return null;
                });
            } catch (Exception e) {
                log.error("流式调用DeepSeek失败", e);
                onError.accept(e);
            }
        }).start();
    }

    private String parseAssistantContent(String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            if (contentNode.isMissingNode() || contentNode.asText().isBlank()) {
                throw new IllegalStateException("DeepSeek响应中未找到 message.content");
            }
            return contentNode.asText();
        } catch (Exception e) {
            throw new IllegalStateException("解析DeepSeek响应失败: " + e.getMessage(), e);
        }
    }
}
