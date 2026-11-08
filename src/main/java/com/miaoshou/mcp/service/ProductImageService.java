package com.miaoshou.mcp.service;

import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.image.ImageAssetManifest;
import org.springframework.stereotype.Service;

@Service
public class ProductImageService {
    private final SnapshotService snapshots;
    private final ProductValidator validator;
    private final ImageAssetExtractor extractor;

    public ProductImageService(SnapshotService snapshots, ProductValidator validator, ImageAssetExtractor extractor) {
        this.snapshots = snapshots;
        this.validator = validator;
        this.extractor = extractor;
    }

    public ImageAssetManifest extract(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new BusinessException("SNAPSHOT_ID_REQUIRED", "An existing local snapshot ID is required");
        }
        var row = snapshots.find(snapshotId);
        var product = snapshots.product(row);
        if (!product.isObject() || !Long.toString(row.detailId()).equals(product.path("detailId").asText())
                || !Long.toString(row.shopId()).equals(product.path("shopId").asText())
                || !row.site().equals(product.path("site").asText())) {
            throw new BusinessException("SNAPSHOT_INVALID", "Snapshot identity does not match the stored product");
        }
        validator.validateSku(product);
        return extractor.extract(row, product);
    }
}
