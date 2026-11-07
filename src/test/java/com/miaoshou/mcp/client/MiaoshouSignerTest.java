package com.miaoshou.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MiaoshouSignerTest {
    @Test
    void matchesIndependentPythonGoldenVectorWithUtf8Body() {
        byte[] body = "{\"title\":\"猫咪 & test\",\"price\":8.99}".getBytes(StandardCharsets.UTF_8);
        assertThat(MiaoshouSigner.sign("fixture-secret", "/open/v1/product/shop/shop/get_shop_list",
                "1700000000", "fixture-key", body))
                .isEqualTo("d439ab34aef4194d7faf184234376f9dba7866a125115db6f1cb19ad2a8079c8");
    }

    @Test
    void rejectsDomainAndQueryInSigningPath() {
        assertThatThrownBy(() -> MiaoshouSigner.sign("s", "https://example.com/open/a", "1", "k", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MiaoshouSigner.sign("s", "/open/a?q=1", "1", "k", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
