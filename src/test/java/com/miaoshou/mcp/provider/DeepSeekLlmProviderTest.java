package com.miaoshou.mcp.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miaoshou.mcp.Fixtures;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.config.LlmProperties;
import com.miaoshou.mcp.provider.llm.DeepSeekLlmProvider;
import com.miaoshou.mcp.provider.llm.LlmProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class DeepSeekLlmProviderTest {
    @Test
    void sendsJsonModeAndKeepsCredentialsOutOfMessages() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            var request = Fixtures.JSON.readTree(exchange.getRequestBody());
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer fixture-provider-key");
            assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_object");
            assertThat(request.path("messages").toString()).doesNotContain("fixture-provider-key");
            byte[] body = "{\"model\":\"actual-model\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var provider = provider(server);
            var result = provider.generate(new LlmProvider.Request("Return JSON", Fixtures.JSON.createObjectNode()));
            assertThat(result.model()).isEqualTo("actual-model");
            assertThat(result.content()).isEqualTo("{\"ok\":true}");
            assertThat(result.finishReason()).isEqualTo("stop");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sanitizesHttpFailureAndDoesNotAutomaticallyRetry() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger count = new AtomicInteger();
        server.createContext("/chat/completions", exchange -> {
            count.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = "fixture-provider-key".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            assertThatThrownBy(() -> provider(server).generate(new LlmProvider.Request("JSON", Fixtures.JSON.createObjectNode())))
                    .isInstanceOf(BusinessException.class).hasMessageNotContaining("fixture-provider-key");
            assertThat(count.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    private DeepSeekLlmProvider provider(HttpServer server) {
        return new DeepSeekLlmProvider(new LlmProperties("deepseek", "fixture-provider-key",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "fixture-model",
                Duration.ofSeconds(5), 1000), WebClient.builder());
    }
}
