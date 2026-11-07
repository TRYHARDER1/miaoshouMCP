package com.miaoshou.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miaoshou.mcp.common.BusinessException;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class WeightPolicy {
    private static final BigDecimal INCREMENT = new BigDecimal("0.01");
    private static final BigDecimal MINIMUM = new BigDecimal("0.001");
    private static final BigDecimal MAXIMUM = new BigDecimal("100");

    public ObjectNode draft(JsonNode original) {
        BigDecimal productWeight = weight(original.path("weight"));
        ObjectNode draft = original.deepCopy();
        draft.put("weight", increased(productWeight));
        var skus = draft.path("skuMap").elements();
        while (skus.hasNext()) {
            ObjectNode sku = (ObjectNode) skus.next();
            JsonNode current = sku.path("weight");
            BigDecimal baseline = current.isMissingNode() || current.isNull() ? productWeight : weight(current);
            sku.put("weight", increased(baseline));
        }
        return draft;
    }

    private static BigDecimal weight(JsonNode value) {
        if (!value.isNumber()) {
            throw new BusinessException("WEIGHT_REQUIRED", "Original product and populated SKU weights must be numeric kg values");
        }
        BigDecimal weight = value.decimalValue();
        if (weight.compareTo(MINIMUM) < 0 || weight.compareTo(MAXIMUM) > 0) {
            throw new BusinessException("WEIGHT_INVALID", "Original weight must be between 0.001 and 100 kg");
        }
        return weight;
    }

    private static BigDecimal increased(BigDecimal baseline) {
        BigDecimal result = baseline.add(INCREMENT);
        if (result.compareTo(MAXIMUM) > 0) {
            throw new BusinessException("WEIGHT_INVALID", "Adding 0.01 kg would exceed 100 kg");
        }
        return result;
    }
}
