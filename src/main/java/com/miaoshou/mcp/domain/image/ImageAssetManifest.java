package com.miaoshou.mcp.domain.image;

import java.util.List;

public record ImageAssetManifest(String snapshotId, long detailId, long shopId, String site,
                                 int totalAssets, int totalReferences, int skippedEmptyReferences,
                                 List<ImageAsset> assets, Summary summary, Validation validation) {
    public ImageAssetManifest {
        assets = List.copyOf(assets);
    }

    /** Counts refer to physical references, including invalid nonempty URLs. */
    public record Summary(int mainImages, int subImages, int skuImages, int detailImages,
                          int unclassified, int candidateMainImages) { }

    public record Issue(String code, String sourcePath, Integer htmlImageIndex) { }

    public record Validation(boolean valid, List<Issue> errors, List<Issue> warnings) {
        public Validation {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }
    }
}
