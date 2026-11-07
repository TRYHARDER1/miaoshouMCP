package com.miaoshou.mcp.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.config.ApiProperties;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class MiaoshouApiClient {
    private final ApiProperties properties;
    private final SecretResolver secrets;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final WebClient client;
    private long nextRequestNanos;

    public MiaoshouApiClient(ApiProperties properties, SecretResolver secrets,
                            ObjectMapper mapper, Clock clock, WebClient.Builder builder) {
        this.properties = properties;
        this.secrets = secrets;
        this.mapper = mapper;
        this.clock = clock;
        this.client = builder.baseUrl(properties.baseUrl().toString())
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build();
    }

    public JsonNode call(ApiEndpoint endpoint, Map<String, ?> request) {
        if (properties.appKey().isBlank()) {
            throw new BusinessException("CONFIG_REQUIRED", "Configure MIAOSHOU_APP_KEY in the process environment");
        }
        String secret = secrets.resolve();
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(request);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("REQUEST_INVALID", "Cannot serialize the API request");
        }
        awaitRequestSlot();
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        String signature = MiaoshouSigner.sign(secret, endpoint.path(), timestamp, properties.appKey(), body);
        JsonNode response;
        try {
            response = client.post().uri(endpoint.path()).contentType(MediaType.APPLICATION_JSON)
                    .header("x-app-key", properties.appKey())
                    .header("x-timestamp", timestamp).header("x-sign", signature)
                    .bodyValue(body).retrieve().bodyToMono(JsonNode.class).block(properties.timeout());
        } catch (Exception exception) {
            // Transport exceptions can include headers or response bodies. Keep their details local.
            throw new BusinessException("API_TRANSPORT_ERROR", "Miaoshou API transport failed; the request was not retried");
        }
        if (response == null || !response.isObject() || !"success".equals(response.path("result").asText())) {
            throw new BusinessException("API_REJECTED", "Miaoshou rejected the query; check account permissions and request limits");
        }
        if (!response.path("data").isContainerNode()) {
            throw new BusinessException("API_RESPONSE_INVALID", "Miaoshou returned an invalid data payload");
        }
        return response.get("data");
    }

    private synchronized void awaitRequestSlot() {
        long remaining;
        while ((remaining = nextRequestNanos - System.nanoTime()) > 0) {
            LockSupport.parkNanos(remaining);
            if (Thread.currentThread().isInterrupted()) {
                throw new BusinessException("REQUEST_CANCELLED", "API query interrupted before dispatch");
            }
        }
        nextRequestNanos = System.nanoTime() + properties.requestInterval().toNanos();
    }
}
