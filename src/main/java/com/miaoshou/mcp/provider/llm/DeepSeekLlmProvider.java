package com.miaoshou.mcp.provider.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.config.LlmProperties;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
public class DeepSeekLlmProvider implements LlmProvider {
    private final LlmProperties properties;
    private final WebClient client;

    public DeepSeekLlmProvider(LlmProperties properties, WebClient.Builder builder) {
        this.properties = properties;
        this.client = builder.baseUrl(properties.baseUrl().toString())
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(4 * 1024 * 1024)).build();
    }

    @Override
    public String name() {
        return "deepseek";
    }

    @Override
    public String model() {
        return properties.model();
    }

    @Override
    public Response generate(Request request) {
        if (!name().equals(properties.provider())) {
            throw new BusinessException("LLM_PROVIDER_UNAVAILABLE", "Configured LLM provider is not installed");
        }
        if (properties.apiKey().isBlank()) {
            throw new BusinessException("LLM_CONFIG_REQUIRED", "Configure DEEPSEEK_API_KEY in the process or user environment");
        }
        JsonNode envelope;
        try {
            envelope = client.post().uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("model", model(), "stream", false, "temperature", 0.2,
                            "reasoning_effort", "none", "max_tokens", properties.maxTokens(),
                            "response_format", Map.of("type", "json_object"),
                            "messages", List.of(Map.of("role", "system", "content", request.systemPrompt()),
                                    Map.of("role", "user", "content", request.input().toString()))))
                    .retrieve().bodyToMono(JsonNode.class).block(properties.timeout());
        } catch (WebClientResponseException exception) {
            throw new BusinessException("LLM_HTTP_" + exception.getStatusCode().value(),
                    "DeepSeek request failed with HTTP " + exception.getStatusCode().value());
        } catch (Exception exception) {
            throw new BusinessException("LLM_TRANSPORT_ERROR", "DeepSeek transport failed; request was not retried");
        }
        if (envelope == null || !envelope.path("choices").isArray() || envelope.path("choices").isEmpty()) {
            throw new BusinessException("LLM_RESPONSE_INVALID", "DeepSeek response has no completion");
        }
        JsonNode choice = envelope.path("choices").get(0);
        return new Response(envelope.path("model").asText(model()), choice.path("message").path("content").asText(""),
                choice.path("finish_reason").asText(""));
    }
}
