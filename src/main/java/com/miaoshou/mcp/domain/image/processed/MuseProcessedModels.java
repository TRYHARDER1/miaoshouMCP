package com.miaoshou.mcp.domain.image.processed;

import com.miaoshou.mcp.domain.image.muse.MuseModels.JobRow;
import com.miaoshou.mcp.domain.image.muse.MuseModels.OriginalFile;
import com.miaoshou.mcp.domain.image.muse.MuseModels.ResultRow;
import java.util.List;

/** M5.4 contracts: integrity is independent of link registration and visual quality. */
public final class MuseProcessedModels {
    private MuseProcessedModels() { }
    public enum State {
        /** Allocated, not claimed. */ PENDING,
        /** Owned bounded network request. */ DOWNLOADING,
        /** Complete local bytes and receipt exist. */ DOWNLOADED,
        /** Technical validation or publication. */ VALIDATING,
        /** Technically validated and published; not visual approval. */ VALIDATED,
        /** Explicit failure requiring recovery or retry. */ FAILED
    }
    /** Internal only; never a tool argument or output.
     * @param job frozen job @param result database-registered source @param original verified original metadata
     * @param position stable zero-based position in this immutable batch, used for a short Windows-safe parent directory */
    public record Source(JobRow job,ResultRow result,OriginalFile original,int position) { }
    /** @param mediaType validated MIME @param actualBytes actual size @param width decoded width
     * @param height decoded height @param sha256 unchanged bytes digest @param extension safe suffix
     * @param validatorVersion integrity policy */
    public record Metadata(String mediaType,long actualBytes,int width,int height,String sha256,String extension,String validatorVersion) { }
    /** @param bytes bounded network response @param mediaType whitelisted response MIME @param httpStatus response code */
    public record Download(byte[] bytes,String mediaType,int httpStatus) { }
    /** @param jobId original job @param batchId original batch @param assetId unchanged image ID
     * @param sourceUrlHash registered URL identity @param originalSha256 frozen original digest
     * @param attemptToken authorized writer @param metadata technical metadata @param savedAt UTC phase time */
    public record Receipt(String jobId,String batchId,String assetId,String sourceUrlHash,String originalSha256,
                           String attemptToken,Metadata metadata,String savedAt) { }
    /** @param jobId owner @param assetId identity @param sourceUrlHash source identity
     * @param originalSha256 frozen original @param status independent processing state
     * @param rowVersion CAS version @param attemptNo ownership generation @param retryCount explicit network retries
     * @param attemptToken writer fence @param leaseUntil processing expiry @param stagingRelativePath private local stage
     * @param processedRelativePath unique published file @param httpStatus safe response status
     * @param mediaType recognized type @param actualBytes actual length @param width decoded width @param height decoded height
     * @param processedSha256 expected file digest @param validatorVersion integrity policy
     * @param errorCode safe failure reason @param failurePhase stage of failure
     * @param downloadStartedAt latest fetch start @param downloadedAt complete byte time @param validatedAt publication time
     * @param createdAt original allocation @param updatedAt last change */
    public record Row(String jobId,String assetId,String sourceUrlHash,String originalSha256,State status,
                      long rowVersion,int attemptNo,int retryCount,String attemptToken,String leaseUntil,
                      String stagingRelativePath,String processedRelativePath,Integer httpStatus,String mediaType,
                      Long actualBytes,Integer width,Integer height,String processedSha256,String validatorVersion,
                      String errorCode,String failurePhase,String downloadStartedAt,String downloadedAt,String validatedAt,
                      String createdAt,String updatedAt) { }
    /** @param assetId original ID @param state persisted or derived state @param rowVersion CAS version
     * @param attemptNo ownership generation @param retryCount explicit retries @param integrityVerified current file verified
     * @param visualReviewStatus always NOT_REVIEWED @param fileHealth OK/MISSING/CORRUPT/UNKNOWN
     * @param metadata recorded technical metadata @param processedPath local published file, nullable
     * @param originalPath local original @param errorCode safe reason @param duplicateContent same hash on another asset
     * @param validatedAt last validation @param updatedAt last state event */
    public record View(String assetId,String state,long rowVersion,int attemptNo,int retryCount,boolean integrityVerified,
                       String visualReviewStatus,String fileHealth,Metadata metadata,String processedPath,String originalPath,
                       String errorCode,boolean duplicateContent,String validatedAt,String updatedAt) { }
    /** @param totalAssets original assets @param registeredCount registered links @param missingLinkCount unregistered
     * @param notStartedCount registered without processing row @param pending pending @param downloading active fetches
     * @param downloaded complete local bytes @param validating integrity work @param validated recorded validated files
     * @param failed explicit failures */
    public record Progress(int totalAssets,int registeredCount,int missingLinkCount,int notStartedCount,
                           int pending,int downloading,int downloaded,int validating,int validated,int failed) { }
    /** @param batchId context @param results per-asset safe views @param missingAssetIds assets without links
     * @param progress consistent state counts */
    public record BatchView(String batchId,List<View> results,List<String> missingAssetIds,Progress progress) {
        public BatchView { results=List.copyOf(results);missingAssetIds=List.copyOf(missingAssetIds); }
    }
}
