package com.miaoshou.mcp.client;

import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.config.ApiProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class SecretResolver {
    private final ApiProperties properties;

    public SecretResolver(ApiProperties properties) {
        this.properties = properties;
    }

    public String resolve() {
        if (!properties.secretEncrypted().isBlank()) {
            return decrypt(properties.secretEncrypted(), properties.secretKey());
        }
        if (properties.appSecret().isBlank()) {
            throw new BusinessException("CONFIG_REQUIRED", "Configure the Miaoshou secret in the process environment");
        }
        return properties.appSecret();
    }

    // Format: Base64(12-byte random nonce || AES-GCM ciphertext with its 16-byte tag).
    public static String decrypt(String encrypted, String encodedKey) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encrypted);
            byte[] key = Base64.getDecoder().decode(encodedKey);
            if (bytes.length < 29 || key.length != 32) {
                throw new IllegalArgumentException("Invalid encrypted configuration");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, Arrays.copyOfRange(bytes, 0, 12)));
            return new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new BusinessException("CONFIG_INVALID", "Cannot decrypt the Miaoshou secret");
        }
    }
}
