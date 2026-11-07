package com.miaoshou.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miaoshou.mcp.common.BusinessException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SecretResolverTest {
    @Test
    void decryptsAuthenticatedConfigurationAndRejectsTampering() throws Exception {
        byte[] key = new byte[32];
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(key);
        new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal("fixture-secret".getBytes(StandardCharsets.UTF_8));
        byte[] payload = ByteBuffer.allocate(12 + ciphertext.length).put(nonce).put(ciphertext).array();
        String encodedKey = Base64.getEncoder().encodeToString(key);
        assertThat(SecretResolver.decrypt(Base64.getEncoder().encodeToString(payload), encodedKey))
                .isEqualTo("fixture-secret");
        payload[payload.length - 1] ^= 1;
        assertThatThrownBy(() -> SecretResolver.decrypt(Base64.getEncoder().encodeToString(payload), encodedKey))
                .isInstanceOf(BusinessException.class).hasMessage("Cannot decrypt the Miaoshou secret");
    }
}
