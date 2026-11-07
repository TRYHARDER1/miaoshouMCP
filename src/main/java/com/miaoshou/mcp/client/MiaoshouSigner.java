package com.miaoshou.mcp.client;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class MiaoshouSigner {
    private MiaoshouSigner() { }

    public static String sign(String secret, String path, String timestamp, String key, byte[] body) {
        if (!path.startsWith("/open/") || path.contains("?") || path.contains("#")) {
            throw new IllegalArgumentException("Signing requires an API path without query or fragment");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((secret + path + timestamp + key).getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return HexFormat.of().formatHex(mac.doFinal(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable");
        }
    }
}
