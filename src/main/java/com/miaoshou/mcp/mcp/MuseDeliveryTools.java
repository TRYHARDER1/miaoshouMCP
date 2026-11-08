package com.miaoshou.mcp.mcp;

import com.miaoshou.mcp.domain.image.muse.MuseDeliveryModels.View;
import com.miaoshou.mcp.service.muse.MuseDeliveryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** External-safe delivery is separate from the legacy internal export tool. */
@Component
public class MuseDeliveryTools {
    private final MuseDeliveryService deliveries;
    public MuseDeliveryTools(MuseDeliveryService deliveries) { this.deliveries=deliveries; }
    @Tool(name="product_image_export_muse_delivery",description="Export an existing published job as a separate safe Muse delivery: only assetIds, filenames, roles, requirements and image copies. No original URLs, SKU keys, references, internal identifiers or result links. Preserves all historical packages; no network.")
    public View export(String batchId) { return deliveries.export(batchId); }
}
