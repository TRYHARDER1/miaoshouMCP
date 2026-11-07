package com.miaoshou.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.ProductRef;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ProductValidator {
    public void validate(JsonNode envelope, ProductRef ref) {
        if (!envelope.isObject() || !"shop".equals(envelope.path("editModel").asText())
                || envelope.path("ossMd5").asText().isBlank()) {
            fail("Expected a shop-mode product with an ossMd5 baseline");
        }
        JsonNode product = envelope.path("shopCollectItemInfo");
        if (!product.isObject() || !Long.toString(ref.detailId()).equals(product.path("detailId").asText())
                || !Long.toString(ref.shopId()).equals(product.path("shopId").asText())) {
            fail("Product detail does not match the requested product and shop");
        }
        JsonNode claimed = envelope.path("claimToShopIds");
        if (claimed.isArray()) {
            boolean belongs = false;
            for (JsonNode id : claimed) {
                belongs |= Long.toString(ref.shopId()).equals(id.asText());
            }
            if (!belongs) {
                fail("Requested shop is not associated with the product");
            }
        }
        validateSku(product);
    }

    public void validateSku(JsonNode product) {
        JsonNode properties = product.path("skuPropertyList");
        JsonNode skuMap = product.path("skuMap");
        if (!properties.isArray() || properties.isEmpty() || !skuMap.isObject() || skuMap.isEmpty()) {
            fail("A product must have SKU properties and a nonempty SKU map");
        }
        Map<String, Integer> dimensions = new HashMap<>();
        for (int index = 0; index < properties.size(); index++) {
            JsonNode values = properties.get(index).path("attrValueList");
            if (!values.isArray() || values.isEmpty()) {
                fail("A SKU dimension has no values");
            }
            for (JsonNode value : values) {
                JsonNode id = value.path("attrValueId");
                if (!id.isTextual() || id.asText().isBlank() || id.asText().contains(";")
                        || dimensions.putIfAbsent(id.asText(), index) != null) {
                    fail("SKU value IDs must be unique nonempty strings without semicolons");
                }
            }
        }
        Set<Set<String>> combinations = new HashSet<>();
        var entries = skuMap.fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            String key = entry.getKey();
            if (key.length() < 3 || !key.startsWith(";") || !key.endsWith(";") || !entry.getValue().isObject()) {
                fail("Invalid SKU map key or payload");
            }
            String[] ids = key.substring(1, key.length() - 1).split(";", -1);
            Set<Integer> seenDimensions = new HashSet<>();
            Set<String> seenIds = new HashSet<>();
            for (String id : ids) {
                Integer dimension = dimensions.get(id);
                if (dimension == null || !seenDimensions.add(dimension) || !seenIds.add(id)) {
                    fail("A SKU key has an unknown ID or repeats a dimension");
                }
            }
            if (seenDimensions.size() != properties.size() || !combinations.add(seenIds)) {
                fail("SKU keys must resolve once per dimension without duplicate combinations");
            }
        }
    }

    public void assertIdentityUnchanged(JsonNode original, JsonNode draft) {
        validateSku(original);
        validateSku(draft);
        Set<String> before = new HashSet<>();
        original.path("skuMap").fieldNames().forEachRemaining(before::add);
        Set<String> after = new HashSet<>();
        draft.path("skuMap").fieldNames().forEachRemaining(after::add);
        if (!before.equals(after) || original.path("skuPropertyList").size() != draft.path("skuPropertyList").size()) {
            fail("SKU map keys and dimensions must be preserved");
        }
        for (int dimension = 0; dimension < original.path("skuPropertyList").size(); dimension++) {
            JsonNode a = original.path("skuPropertyList").get(dimension);
            JsonNode b = draft.path("skuPropertyList").get(dimension);
            if (!a.path("attrId").equals(b.path("attrId"))
                    || a.path("attrValueList").size() != b.path("attrValueList").size()) {
                fail("SKU property IDs and structure must be preserved");
            }
            for (int value = 0; value < a.path("attrValueList").size(); value++) {
                if (!a.path("attrValueList").get(value).path("attrValueId")
                        .equals(b.path("attrValueList").get(value).path("attrValueId"))) {
                    fail("SKU value IDs and order must be preserved");
                }
            }
        }
    }

    private static void fail(String message) {
        throw new BusinessException("PRODUCT_INVALID", message);
    }
}
