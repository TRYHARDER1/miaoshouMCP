package com.miaoshou.mcp.domain.image;

import java.util.List;

/** sourcePath is an RFC 6901 pointer relative to shopCollectItemInfo. */
public record ImageReference(ReferenceType referenceType, ImageAssetType assetType,
                             String sourcePath, Integer arrayIndex, List<String> skuMapKeys,
                             Integer htmlImageIndex, int sequence, boolean candidateMain) {
    public ImageReference {
        skuMapKeys = List.copyOf(skuMapKeys);
    }

    public enum ReferenceType { PRODUCT_IMAGE, SKU_IMAGE, DETAIL_IMAGE }
}
