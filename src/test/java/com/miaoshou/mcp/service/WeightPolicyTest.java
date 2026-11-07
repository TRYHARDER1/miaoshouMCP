package com.miaoshou.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miaoshou.mcp.Fixtures;
import com.miaoshou.mcp.common.BusinessException;
import org.junit.jupiter.api.Test;

class WeightPolicyTest {
    private final WeightPolicy policy = new WeightPolicy();

    @Test
    void incrementsEachOriginalIndependentlyAndFallsBackForNullAndMissing() throws Exception {
        ObjectNode original = Fixtures.product();
        ObjectNode untouched = original.deepCopy();
        ObjectNode draft = policy.draft(original);
        assertThat(draft.path("weight").decimalValue()).isEqualByComparingTo("0.51");
        assertThat(draft.at("/skuMap/;a;b;/weight").decimalValue()).isEqualByComparingTo("0.46");
        assertThat(draft.at("/skuMap/;a;d;/weight").decimalValue()).isEqualByComparingTo("0.61");
        assertThat(draft.at("/skuMap/;b;c;/weight").decimalValue()).isEqualByComparingTo("0.51");
        assertThat(draft.at("/skuMap/;c;d;/weight").decimalValue()).isEqualByComparingTo("0.51");
        assertThat(original).isEqualTo(untouched);
        assertThat(policy.draft(original)).isEqualTo(draft);
        assertThat(draft.path("platformField")).isEqualTo(original.path("platformField"));
    }

    @Test
    void zeroSkuWeightIsInvalidAndDoesNotUseTheMissingWeightFallback() throws Exception {
        ObjectNode original = Fixtures.product();
        ((ObjectNode) original.at("/skuMap/;a;b;")).put("weight", 0);
        assertThatThrownBy(() -> policy.draft(original)).isInstanceOf(BusinessException.class);
    }

    @Test
    void missingProductWeightBlocksEvenWhenSkusHaveWeights() throws Exception {
        ObjectNode original = Fixtures.product();
        original.remove("weight");
        assertThatThrownBy(() -> policy.draft(original)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsOverflowAfterAddingIncrement() throws Exception {
        ObjectNode original = Fixtures.product();
        original.put("weight", 100);
        assertThatThrownBy(() -> policy.draft(original)).isInstanceOf(BusinessException.class);
    }
}
