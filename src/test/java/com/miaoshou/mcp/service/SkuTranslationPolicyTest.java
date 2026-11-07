package com.miaoshou.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miaoshou.mcp.Fixtures;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.AiModels.PropertyTranslation;
import com.miaoshou.mcp.domain.AiModels.SkuTranslation;
import com.miaoshou.mcp.domain.AiModels.ValueTranslation;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkuTranslationPolicyTest {
    private final SkuTranslationPolicy policy = new SkuTranslationPolicy(new ProductValidator(), new GeneratedTextValidator());

    @Test
    void matchesByIdsWhilePreservingOriginalArrayOrderAndAllSkuPayloads() throws Exception {
        var original = Fixtures.product();
        var baseline = original.deepCopy();
        var draft = policy.apply(original, translation("c", "a"));
        assertThat(draft.at("/skuPropertyList/0/attrValueList/0/attrValueId").asText()).isEqualTo("a");
        assertThat(draft.at("/skuPropertyList/0/attrValueList/0/attrValue").asText()).isEqualTo("Red tone");
        assertThat(draft.path("skuMap")).isEqualTo(original.path("skuMap"));
        assertThat(draft.at("/skuPropertyList/0/attrValueList/0/imgUrl"))
                .isEqualTo(original.at("/skuPropertyList/0/attrValueList/0/imgUrl"));
        assertThat(original).isEqualTo(baseline);
    }

    @Test
    void rejectsDuplicateMissingAndCrossDimensionValues() throws Exception {
        var original = Fixtures.product();
        assertThatThrownBy(() -> policy.apply(original, translation("a", "a")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.apply(original, translation("b", "a")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.apply(original, new SkuTranslation(List.of())))
                .isInstanceOf(BusinessException.class);
    }

    private SkuTranslation translation(String firstId, String secondId) {
        return new SkuTranslation(List.of(
                new PropertyTranslation(1, "Pack", List.of(new ValueTranslation("d", "Bag packaging"),
                        new ValueTranslation("b", "Loose packaging"))),
                new PropertyTranslation(0, "Color", List.of(new ValueTranslation(firstId, "Blue tone"),
                        new ValueTranslation(secondId, "Red tone")))));
    }
}
