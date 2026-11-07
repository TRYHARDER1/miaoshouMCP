package com.miaoshou.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaoshou.mcp.client.ApiEndpoint;
import com.miaoshou.mcp.client.MiaoshouApiClient;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.ProductRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ProductReadService {
    private final MiaoshouApiClient api;
    private final ProductValidator validator;

    public ProductReadService(MiaoshouApiClient api, ProductValidator validator) {
        this.api = api;
        this.validator = validator;
    }

    public JsonNode shops(String site, int pageNo, int pageSize) {
        page(pageNo, pageSize, 100);
        return api.call(ApiEndpoint.SHOPS, Map.of("platform", "tiktok", "site", site(site),
                "pageNo", pageNo, "pageSize", pageSize));
    }

    public Map<String, Object> collectItems(long shopId, int pageNo, int pageSize) {
        if (shopId <= 0) {
            throw new IllegalArgumentException("shopId must be a positive integer");
        }
        page(pageNo, pageSize, 500);
        JsonNode result = api.call(ApiEndpoint.COLLECT_ITEMS, Map.of("pageNo", pageNo, "pageSize", pageSize,
                "filter", Map.of("status", "notPublished")));
        if (!result.path("detailList").isArray()) {
            throw new BusinessException("API_RESPONSE_INVALID", "Expected a detailList array");
        }
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode item : result.path("detailList")) {
            for (JsonNode shop : item.path("collectBoxDetailShopList")) {
                if (Long.toString(shopId).equals(shop.path("shopId").asText())) {
                    matches.add(item.deepCopy());
                    break;
                }
            }
        }
        return Map.of("shopId", shopId, "pageNo", pageNo, "pageSize", pageSize,
                "sourceRowCount", result.path("detailList").size(), "detailList", matches,
                "hasMoreSourcePages", result.path("detailList").size() == pageSize,
                "paginationScope", "Account-level source page, then filtered by shop association");
    }

    public JsonNode detail(ProductRef ref) {
        JsonNode result = api.call(ApiEndpoint.SHOP_DETAIL, Map.of("detailId", ref.detailId(), "shopId", ref.shopId()));
        validator.validate(result, ref);
        return result;
    }

    public JsonNode templates(String site, int pageNo) {
        page(pageNo, 20, 20);
        return api.call(ApiEndpoint.PRICE_TEMPLATES, Map.of("site", site(site), "pageNo", pageNo, "pageSize", 20));
    }

    private static String site(String site) {
        if (site == null || !site.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("site must be a two-letter uppercase code such as SG");
        }
        return site;
    }

    private static void page(int pageNo, int pageSize, int maximum) {
        if (pageNo < 1 || pageSize < 1 || pageSize > maximum) {
            throw new IllegalArgumentException("Invalid page number or page size");
        }
    }
}
