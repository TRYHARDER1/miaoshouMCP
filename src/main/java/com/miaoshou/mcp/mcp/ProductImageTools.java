package com.miaoshou.mcp.mcp;

import com.miaoshou.mcp.domain.image.ImageAssetManifest;
import com.miaoshou.mcp.service.ProductImageService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class ProductImageTools {
    private final ProductImageService images;

    public ProductImageTools(ProductImageService images) { this.images = images; }

    @Tool(name = "product_image_extract_assets", description = "Extract a deterministic image asset manifest from an existing local snapshot only. Preserves every physical reference and all original SKU keys. Product images remain UNCLASSIFIED with first-position main candidate. Check validation errors and warnings. No network calls or writes.")
    public ImageAssetManifest extract(String snapshotId) {
        return images.extract(snapshotId);
    }
}
