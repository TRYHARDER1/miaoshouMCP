package com.miaoshou.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.AiModels.SkuTranslation;
import com.miaoshou.mcp.domain.AiModels.ValueTranslation;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class SkuTranslationPolicy {
    private final ProductValidator products;
    private final GeneratedTextValidator text;

    public SkuTranslationPolicy(ProductValidator products, GeneratedTextValidator text) {
        this.products = products;
        this.text = text;
    }

    public ObjectNode apply(JsonNode original, SkuTranslation translation) {
        products.validateSku(original);
        if (translation.properties() == null || translation.properties().size() != original.path("skuPropertyList").size()) {
            fail("SKU translation must cover every dimension");
        }
        ObjectNode draft = original.deepCopy();
        Set<Integer> seen = new HashSet<>();
        for (var property : translation.properties()) {
            int index = property.propertyIndex();
            if (index < 0 || index >= draft.path("skuPropertyList").size() || !seen.add(index)) {
                fail("Unknown or repeated SKU dimension index");
            }
            ObjectNode target = (ObjectNode) draft.path("skuPropertyList").get(index);
            text.translatedLabel(target.path("attrName").asText(), property.attrName());
            if (property.values() == null || property.values().size() != target.path("attrValueList").size()) {
                fail("SKU translation must cover every value");
            }
            Map<String, ValueTranslation> values;
            try {
                values = property.values().stream().collect(Collectors.toMap(ValueTranslation::attrValueId, Function.identity()));
            } catch (IllegalStateException | NullPointerException exception) {
                throw new BusinessException("AI_OUTPUT_INVALID", "Repeated or null SKU value IDs");
            }
            target.put("attrName", property.attrName());
            for (JsonNode value : target.path("attrValueList")) {
                ValueTranslation translated = values.remove(value.path("attrValueId").asText());
                if (translated == null) {
                    fail("SKU translation contains a missing or cross-dimension value ID");
                }
                text.translatedLabel(value.path("attrValue").asText(), translated.attrValue());
                ((ObjectNode) value).put("attrValue", translated.attrValue());
            }
            if (!values.isEmpty()) {
                fail("SKU translation contains unknown value IDs");
            }
        }
        products.assertIdentityUnchanged(original, draft);
        return draft;
    }

    private static void fail(String message) {
        throw new BusinessException("AI_OUTPUT_INVALID", message);
    }
}
