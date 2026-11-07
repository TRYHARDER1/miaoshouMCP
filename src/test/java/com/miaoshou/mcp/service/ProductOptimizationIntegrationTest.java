package com.miaoshou.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;

import com.miaoshou.mcp.Fixtures;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.AiModels.Analysis;
import com.miaoshou.mcp.domain.AiModels.Content;
import com.miaoshou.mcp.domain.AiModels.Evidence;
import com.miaoshou.mcp.domain.AiModels.PropertyTranslation;
import com.miaoshou.mcp.domain.AiModels.SkuTranslation;
import com.miaoshou.mcp.domain.AiModels.ValueTranslation;
import com.miaoshou.mcp.domain.ProductRef;
import com.miaoshou.mcp.provider.llm.LlmProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"spring.ai.mcp.server.enabled=false", "spring.datasource.url=jdbc:sqlite::memory:"})
class ProductOptimizationIntegrationTest {
    @MockitoBean ProductReadService products;
    @MockitoBean LlmProvider provider;
    @Autowired SnapshotService snapshots;
    @Autowired ProductOptimizationService optimization;
    @Autowired DraftService drafts;
    @Autowired AiGenerationService generations;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setup() throws Exception {
        when(products.detail(any())).thenReturn(Fixtures.envelope());
        when(provider.name()).thenReturn("deepseek");
        when(provider.model()).thenReturn("fixture-model");
        when(provider.generate(any())).thenAnswer(invocation -> {
            LlmProvider.Request request = invocation.getArgument(0);
            Object output = request.input().has("properties") ? sku()
                    : request.input().has("analysis") ? content() : analysis();
            return new LlmProvider.Response("fixture-model", Fixtures.JSON.writeValueAsString(output), "stop");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void completeWorkflowPreservesSnapshotAndProtectedFieldsAndAuditsEveryGeneration() {
        var snapshot = snapshots.capture(new ProductRef(17, 23));
        var result = optimization.optimize(snapshot.id(), List.of("toy"));
        var preview = (Map<String, Object>) result.get("draft");
        var product = Fixtures.JSON.valueToTree(preview.get("draftProduct"));
        var original = snapshots.product(snapshot);
        assertThat(product.path("title").asText()).isEqualTo(content().title());
        assertThat(product.path("skuMap")).isEqualTo(original.path("skuMap"));
        assertThat(product.path("weight")).isEqualTo(original.path("weight"));
        assertThat(product.path("productAttributes")).isEqualTo(original.path("productAttributes"));
        assertThat(snapshots.find(snapshot.id()).originalJson()).isEqualTo(snapshot.originalJson());
        assertThat(preview.get("status")).isEqualTo("PENDING_REVIEW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ms_ai_generation WHERE snapshot_id=? AND validation_status='STRUCTURE_VALIDATED'",
                Integer.class, snapshot.id())).isEqualTo(3);
        assertThat(generations.view((String) result.get("analysisId")).reviewStatus()).isEqualTo("PENDING_REVIEW");
        var inputSummary = generations.view((String) result.get("contentGenerationId")).inputSummary();
        assertThat(inputSummary.path("analysisGenerationId").asText()).isEqualTo(result.get("analysisId"));
        assertThat(inputSummary.at("/keywords/keywords/0/source").asText()).isEqualTo("manual");
        assertThat(drafts.preview((String) preview.get("draftId")).get("draftProduct")).isEqualTo(preview.get("draftProduct"));
    }

    @Test
    void invalidOutputLeavesFailureRecordAndCreatesNoDraft() {
        var snapshot = snapshots.capture(new ProductRef(17, 23));
        doReturn(new LlmProvider.Response("fixture-model", "{}", "stop")).when(provider).generate(any());
        assertThatThrownBy(() -> optimization.optimize(snapshot.id(), List.of())).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ms_product_draft WHERE snapshot_id=?", Integer.class, snapshot.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ms_ai_generation WHERE snapshot_id=? AND validation_status='FAILED'",
                Integer.class, snapshot.id())).isEqualTo(1);
    }

    private Analysis analysis() {
        return new Analysis("Toy", "Play", List.of(), List.of(), List.of("Color options"), List.of(), List.of("toy"),
                List.of(evidence("Toy"), evidence("Play"), evidence("Color options")));
    }

    private Content content() {
        String title = "Example Toy with Color and Packaging Options";
        return new Content(title, "Explore toy options", List.of("Choose a color"), List.of(), List.of(), List.of(),
                List.of(evidence(title), evidence("Explore toy options"), evidence("Choose a color")));
    }

    private Evidence evidence(String claim) {
        return new Evidence(claim, "/title", "toy");
    }

    private SkuTranslation sku() {
        return new SkuTranslation(List.of(
                new PropertyTranslation(0, "Color", List.of(new ValueTranslation("a", "Red tone"), new ValueTranslation("c", "Blue tone"))),
                new PropertyTranslation(1, "Packaging", List.of(new ValueTranslation("b", "Loose"), new ValueTranslation("d", "Bag")))));
    }
}
