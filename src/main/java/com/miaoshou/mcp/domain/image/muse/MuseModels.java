package com.miaoshou.mcp.domain.image.muse;

import com.miaoshou.mcp.domain.image.ImageAsset;
import java.util.List;

/** Manual handoff records, independent of execution leases and image quality. */
public final class MuseModels {
    private MuseModels() { }
    public enum HandoffStatus {
        /** Export reservation, not an image execution claim. */ CREATING,
        /** Original acquisition or publication failed; safe to retry. */ EXPORT_FAILED,
        /** Complete local package ready. */ EXPORTED,
        /** User explicitly confirmed handoff to Muse. */ WAITING_MUSE,
        /** Some result URLs registered. */ PARTIALLY_RECEIVED,
        /** All result URLs registered, not validated. */ LINKS_RECEIVED
    }
    /** @param snapshotId immutable source @param batchPlanId existing plan
     * @param batchId existing batch @param batchNumber one-based display number
     * @param totalBatches plan size @param market target market @param language target language
     * @param assets complete original assets, in sequence */
    public record Context(String snapshotId, String batchPlanId, String batchId, int batchNumber,
                          int totalBatches, String market, String language, List<ImageAsset> assets) {
        public Context { assets = List.copyOf(assets); }
    }
    /** @param assetId original identity @param fileName controlled original filename
     * @param mediaType verified MIME @param bytes file size @param width decoded width
     * @param height decoded height @param sha256 exact original file digest */
    public record OriginalFile(String assetId, String fileName, String mediaType, long bytes,
                               int width, int height, String sha256) { }
    /** @param jobId stable export identity @param version fixed explicit version
     * @param templateVersion frozen Prompt version @param context immutable source context
     * @param originals actual verified local originals */
    public record PackageManifest(String jobId, int version, String templateVersion, Context context,
                                   List<OriginalFile> originals) {
        public PackageManifest { originals = List.copyOf(originals); }
    }
    /** @param jobId stable identity @param batchId unique original batch @param version frozen version
     * @param status handoff state @param contextJson frozen source context
     * @param packageJson published manifest, nullable while creating
     * @param buildToken fences concurrent exporters @param leaseUntil export reservation expiry
     * @param errorCode safe error code @param createdAt UTC creation @param updatedAt UTC update */
    public record JobRow(String jobId, String batchId, int version, HandoffStatus status, String contextJson,
                         String packageJson, String buildToken, String leaseUntil, String errorCode,
                         String createdAt, String updatedAt) { }
    /** @param assetId must belong to batch @param downloadUrl user-submitted URL, registered only */
    public record ResultInput(String assetId, String downloadUrl) { }
    /** @param jobId owning package @param assetId original identity @param downloadUrl exact registered URL
     * @param urlHash canonical URL identity digest @param registeredAt UTC registration
     * @param status always LINK_REGISTERED @param downloadVerified always false in M5.3 */
    public record ResultRow(String jobId, String assetId, String downloadUrl, String urlHash,
                            String registeredAt, String status, boolean downloadVerified) { }
    /** @param jobId package ID @param batchId batch ID @param version package version
     * @param status handoff state @param manifest complete assets and originals @param prompt generated English instructions
     * @param exportPath absolute local package directory @param imageCount unique image count
     * @param linksFileStatus SYNCED or SYNC_PENDING derived-file status */
    public record ExportView(String jobId, String batchId, int version, HandoffStatus status,
                             PackageManifest manifest, String prompt, String exportPath, int imageCount,
                             String linksFileStatus) { }
    /** @param jobId package ID @param batchId batch ID @param status handoff state
     * @param results persisted links @param missingAssetIds unreceived assets
     * @param linksFileStatus derived projection synchronization state */
    public record ResultsView(String jobId, String batchId, HandoffStatus status, List<ResultRow> results,
                              List<String> missingAssetIds, String linksFileStatus) {
        public ResultsView { results = List.copyOf(results); missingAssetIds = List.copyOf(missingAssetIds); }
    }
}
