package com.miaoshou.mcp.domain.image.muse;

import java.util.List;

/** Explicit external whitelist: never serialize a Context, ImageAsset or result link into this contract. */
public final class MuseDeliveryModels {
    private MuseDeliveryModels() { }
    /** @param assetId unchanged image identity
     * @param fileName relative supplied image filename
     * @param roles processing roles; candidate remains a candidate
     * @param instructions independent image requirements
     * @param deliveryOrder one-based order within this delivery only */
    public record Asset(String assetId,String fileName,List<String> roles,String instructions,int deliveryOrder) {
        public Asset { roles=List.copyOf(roles); }
    }
    /** @param deliverySchemaVersion external schema version, not job.version
     * @param policyVersion sanitizer policy
     * @param deliveryId opaque stable delivery identity
     * @param market target market @param language target language
     * @param assets whitelist-only assets */
    public record Manifest(int deliverySchemaVersion,String policyVersion,String deliveryId,
                           String market,String language,List<Asset> assets) {
        public Manifest { assets=List.copyOf(assets); }
    }
    /** @param manifest safe delivery metadata @param prompt safe external instructions
     * @param exportPath operator-local directory, never included in external manifest */
    public record View(Manifest manifest,String prompt,String exportPath) { }
}
