package com.miaoshou.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miaoshou.mcp.Fixtures;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.config.ApiProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class MiaoshouApiClientTest {
    @Test
    void sendsExactlySignedBytesAndDoesNotRetryBusinessFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicReference<String> signature = new AtomicReference<>();
        server.createContext(ApiEndpoint.SHOPS.path(), exchange -> {
            calls.incrementAndGet();
            received.set(exchange.getRequestBody().readAllBytes());
            signature.set(exchange.getRequestHeaders().getFirst("x-sign"));
            byte[] response = "{\"result\":\"fail\",\"code\":\"accountApiQpsRateLimit\",\"message\":\"fixture-secret\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ApiProperties properties = new ApiProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "fixture-key", "fixture-secret", "", "", Duration.ZERO, Duration.ofSeconds(5));
            var client = new MiaoshouApiClient(properties, new SecretResolver(properties), Fixtures.JSON,
                    Clock.fixed(Instant.ofEpochSecond(1700000000), ZoneOffset.UTC), WebClient.builder());
            Map<String, Object> body = Map.of("title", "猫咪 & test");
            assertThatThrownBy(() -> client.call(ApiEndpoint.SHOPS, body))
                    .isInstanceOf(BusinessException.class).hasMessageNotContaining("fixture-secret");
            assertThat(calls.get()).isEqualTo(1);
            assertThat(received.get()).isEqualTo(Fixtures.JSON.writeValueAsBytes(body));
            assertThat(signature.get()).isEqualTo(MiaoshouSigner.sign("fixture-secret", ApiEndpoint.SHOPS.path(),
                    "1700000000", "fixture-key", received.get()));
        } finally {
            server.stop(0);
        }
    }
}
