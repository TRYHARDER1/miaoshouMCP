package com.miaoshou.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaoshou.mcp.domain.ProductRef;
import com.miaoshou.mcp.domain.SnapshotRow;
import com.miaoshou.mcp.service.DraftService;
import com.miaoshou.mcp.service.ProductReadService;
import com.miaoshou.mcp.service.SnapshotService;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class ProductTools {
    private final ProductReadService products;
    private final SnapshotService snapshots;
    private final DraftService drafts;

    public ProductTools(ProductReadService products, SnapshotService snapshots, DraftService drafts) {
        this.products = products;
        this.snapshots = snapshots;
        this.drafts = drafts;
    }

    @Tool(name = "miaoshou_list_shops", description = "Query a source page of TikTok shops for a site such as SG.")
    public JsonNode shops(String site, int pageNo, int pageSize) {
        return products.shops(site, pageNo, pageSize);
    }

    @Tool(name = "miaoshou_list_collect_items", description = "Read one account-level unpublished collect-box source page, filtered by shop association. Follow source pagination even if no matches are returned.")
    public Map<String, Object> items(long shopId, int pageNo, int pageSize) {
        return products.collectItems(shopId, pageNo, pageSize);
    }

    @Tool(name = "miaoshou_get_product_detail", description = "Read the complete shop-mode product, validate its shop identity and SKU relations, and preserve unknown fields.")
    public JsonNode detail(long detailId, long shopId) {
        return products.detail(new ProductRef(detailId, shopId));
    }

    @Tool(name = "miaoshou_list_price_templates", description = "Read pricing configuration. The confirmed SG template is ID 1953645 with actual profitPercent 30; formulas await independent validation.")
    public JsonNode templates(String site, int pageNo) {
        return products.templates(site, pageNo);
    }

    @Tool(name = "product_capture_snapshot", description = "Read fresh detail and persist an immutable local snapshot including the complete response and ossMd5. Returns its snapshotId.")
    public Map<String, Object> snapshot(long detailId, long shopId) {
        SnapshotRow row = snapshots.capture(new ProductRef(detailId, shopId));
        return Map.of("snapshotId", row.id(), "detailId", row.detailId(), "shopId", row.shopId(),
                "site", row.site(), "ossMd5", row.ossMd5(), "createdAt", row.createdAt());
    }

    @Tool(name = "product_prepare_weight_draft", description = "Create a local draft from an immutable snapshot. Add 0.01 kg to product and each SKU independently, fallback to original product weight for missing SKU weight. Repeated calls for the same snapshot return the same draft.")
    public Map<String, Object> weightDraft(@ToolParam(description = "ID returned by product_capture_snapshot") String snapshotId) {
        return drafts.weightDraft(snapshotId);
    }

    @Tool(name = "product_preview_changes", description = "Return a local pending-review draft and its field-by-field JSON Pointer diff against its immutable snapshot.")
    public Map<String, Object> preview(String draftId) {
        return drafts.preview(draftId);
    }
}
