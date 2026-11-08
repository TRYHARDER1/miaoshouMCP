package com.miaoshou.mcp.service;

import com.miaoshou.mcp.Fixtures;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.ProductRef;
import com.miaoshou.mcp.provider.llm.LlmProvider;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"spring.ai.mcp.server.enabled=false", "spring.datasource.url=jdbc:sqlite::memory:"})
class ProductImageIntegrationTest {
    @MockitoBean ProductReadService products;
    @MockitoBean LlmProvider provider;
    @Autowired SnapshotService snapshots;
    @Autowired ProductImageService images;
    @Autowired ToolCallbackProvider callbacks;
    @Autowired JdbcTemplate jdbc;

    @Test
    void registeredToolUsesOnlyExistingSnapshotAndDoesNotWriteOrCallProviders() throws Exception {
        when(products.detail(new ProductRef(17, 23))).thenReturn(Fixtures.envelope());
        var snapshot = snapshots.capture(new ProductRef(17, 23));
        clearInvocations(products, provider);
        var before = jdbc.queryForList("SELECT * FROM ms_product_snapshot");
        var drafts = jdbc.queryForList("SELECT * FROM ms_product_draft");
        var generations = jdbc.queryForList("SELECT * FROM ms_ai_generation");
        var tools = callbacks.getToolCallbacks();
        assertThat(tools).hasSize(34);
        var tool = Arrays.stream(tools).filter(t -> t.getToolDefinition().name().equals("product_image_extract_assets"))
                .findFirst().orElseThrow();
        assertThat(Fixtures.JSON.readTree(tool.getToolDefinition().inputSchema()).path("properties").size()).isEqualTo(1);
        String args = Fixtures.JSON.createObjectNode().put("snapshotId", snapshot.id()).toString();
        assertThat(tool.call(args)).isEqualTo(tool.call(args));
        assertThat(Fixtures.JSON.readTree(tool.call(args)).path("totalReferences").asInt()).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT * FROM ms_product_snapshot")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM ms_product_draft")).isEqualTo(drafts);
        assertThat(jdbc.queryForList("SELECT * FROM ms_ai_generation")).isEqualTo(generations);
        assertThat(snapshots.find(snapshot.id()).originalJson()).isEqualTo(snapshot.originalJson());
        verifyNoInteractions(products, provider);
    }

    @Test
    void rejectsMissingSnapshotAndCorruptSkuRelations() throws Exception {
        assertThatThrownBy(() -> images.extract(" ")).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("SNAPSHOT_ID_REQUIRED");
        assertThatThrownBy(() -> images.extract("missing")).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("SNAPSHOT_NOT_FOUND");
        var envelope = Fixtures.envelope();
        ((com.fasterxml.jackson.databind.node.ObjectNode) envelope.path("shopCollectItemInfo").path("skuMap"))
                .putObject(";unknown;");
        when(products.detail(new ProductRef(17, 23))).thenReturn(envelope);
        var snapshot = snapshots.capture(new ProductRef(17, 23));
        clearInvocations(products, provider);
        assertThatThrownBy(() -> images.extract(snapshot.id())).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("PRODUCT_INVALID");
        verifyNoInteractions(products, provider);
    }
}
