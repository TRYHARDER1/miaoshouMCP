package com.miaoshou.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.jsoup.Jsoup;

public final class ProductFacts {
    private static final Set<String> FIELDS = Set.of("title", "notes", "site", "weight", "packageLength",
            "packageWidth", "packageHeight", "productAttributes", "skuPropertyList");

    private ProductFacts() { }

    public static ObjectNode extract(JsonNode product) {
        ObjectNode facts = product.deepCopy();
        facts.retain(FIELDS);
        facts.put("notes", Jsoup.parseBodyFragment(product.path("notes").asText()).text());
        for (JsonNode property : facts.path("skuPropertyList")) {
            for (JsonNode value : property.path("attrValueList")) {
                ((ObjectNode) value).remove(Set.of("imgUrl", "supplementarySkuImageUrls"));
            }
        }
        return facts;
    }
}
