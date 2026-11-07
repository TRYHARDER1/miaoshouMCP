package com.miaoshou.mcp.domain.image;

import java.util.List;

/** URL identity digest is assetId; sha256 is reserved for downloaded content. */
public record ImageAsset(String assetId, String originalUrl, List<ImageAssetType> assetTypes,
                         int sequence, List<ImageReference> references, String sha256, Status status) {
    public ImageAsset {
        assetTypes = List.copyOf(assetTypes);
        references = List.copyOf(references);
    }

    public enum Status { PENDING, INVALID_URL }
}
