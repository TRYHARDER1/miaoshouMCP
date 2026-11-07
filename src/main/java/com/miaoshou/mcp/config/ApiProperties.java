package com.miaoshou.mcp.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("miaoshou")
public record ApiProperties(URI baseUrl, String appKey, String appSecret,
                            String secretEncrypted, String secretKey,
                            Duration requestInterval, Duration timeout) {
    @Override
    public String toString() {
        return "ApiProperties[baseUrl=" + baseUrl + ", credentials=redacted]";
    }
}
