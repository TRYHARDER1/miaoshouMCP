package com.miaoshou.mcp.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("llm")
public record LlmProperties(String provider, String apiKey, URI baseUrl, String model,
                            Duration timeout, int maxTokens) {
    @Override
    public String toString() {
        return "LlmProperties[provider=" + provider + ", model=" + model + ", credentials=redacted]";
    }
}
