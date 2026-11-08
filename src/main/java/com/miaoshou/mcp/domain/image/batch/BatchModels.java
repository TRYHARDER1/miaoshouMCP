package com.miaoshou.mcp.domain.image.batch;

import com.miaoshou.mcp.domain.image.ImageAsset;
import com.miaoshou.mcp.domain.image.ImageAssetManifest.Validation;
import java.util.List;

/** Persisted scheduling records; COMPLETED is not evidence of image optimization. */
public final class BatchModels {
    private BatchModels() { }

    public enum BatchStatus {
        /** Allocated, with work waiting. */ PENDING,
        /** At least one task has an active attempt. */ PROCESSING,
        /** New claims blocked; existing progress retained. */ PAUSED,
        /** All tasks terminal, without failures. */ COMPLETED,
        /** At least one failed task needs explicit retry. */ FAILED,
        /** Cancelled permanently; unfinished tasks skipped. */ CANCELLED
    }

    public enum TaskStatus {
        /** Awaiting a claim. */ PENDING,
        /** Leased to one attempt. */ PROCESSING,
        /** Scheduling result recorded, not quality approval. */ COMPLETED,
        /** Failed, requiring explicit retry. */ FAILED,
        /** Intentionally not processed. */ SKIPPED
    }

    /** @param id persisted plan ID
     * @param snapshotId unique immutable snapshot
     * @param batchSize frozen size, 1–4
     * @param manifestHash digest of complete M5.1 manifest
     * @param totalAssets unique manifest assets
     * @param createdAt UTC creation time
     * @param updatedAt UTC last state change */
    public record PlanRow(String id, String snapshotId, int batchSize, String manifestHash,
                          int totalAssets, String createdAt, String updatedAt) { }

    /** @param id persisted batch ID
     * @param planId owning plan
     * @param sequence zero-based batch order
     * @param status persisted aggregate or control override
     * @param createdAt UTC creation time
     * @param updatedAt UTC last change */
    public record BatchRow(String id, String planId, int sequence, BatchStatus status,
                           String createdAt, String updatedAt) { }

    /** @param planId owning plan
     * @param batchId assigned batch, never reassigned
     * @param assetId M5.1 stable asset identity
     * @param sequence original asset order
     * @param status scheduling state
     * @param errorCode safe failure/recovery code, nullable
     * @param retryCount explicit retries, initially zero
     * @param attemptToken fences previous attempts, nullable
     * @param leaseUntil UTC expiry for PROCESSING, nullable
     * @param startedAt latest attempt start, nullable
     * @param completedAt latest terminal result time, nullable
     * @param createdAt UTC allocation time
     * @param updatedAt UTC last change */
    public record TaskRow(String planId, String batchId, String assetId, int sequence, TaskStatus status,
                          String errorCode, int retryCount, String attemptToken, String leaseUntil,
                          String startedAt, String completedAt, String createdAt, String updatedAt) { }

    /** @param task persisted state and attempt metadata
     * @param asset exact M5.1 asset with all original references */
    public record TaskView(TaskRow task, ImageAsset asset) { }

    /** @param batch persisted batch metadata
     * @param tasks ordered tasks and their reconstructed assets */
    public record BatchView(BatchRow batch, List<TaskView> tasks) {
        public BatchView { tasks = List.copyOf(tasks); }
    }

    /** @param totalAssets unique assets
     * @param assigned persisted task count
     * @param pending includes pending tasks in paused batches
     * @param processing active attempts
     * @param completed scheduling completions, not optimization certification
     * @param failed tasks needing explicit retry
     * @param skipped intentionally skipped assets
     * @param remaining pending + processing + failed
     * @param eligiblePending currently claimable pending tasks
     * @param totalBatches all batches
     * @param completedBatches batches with all tasks terminal
     * @param pausedBatches explicitly paused batches
     * @param cancelledBatches permanently cancelled batches */
    public record Progress(int totalAssets, int assigned, int pending, int processing, int completed,
                           int failed, int skipped, int remaining, int eligiblePending, int totalBatches,
                           int completedBatches, int pausedBatches, int cancelledBatches) { }

    /** @param batchPlanId stable persisted plan identity
     * @param snapshotId immutable context
     * @param batchSize frozen batch size
     * @param totalAssets manifest asset count
     * @param totalBatches persisted batches
     * @param totalAssigned persisted task count
     * @param totalSkipped skipped task count (not empty references)
     * @param batches ordered complete task views
     * @param progress consistent state counts
     * @param validation original manifest warnings and validation */
    public record PlanView(String batchPlanId, String snapshotId, int batchSize, int totalAssets,
                           int totalBatches, int totalAssigned, int totalSkipped, List<BatchView> batches,
                           Progress progress, Validation validation) {
        public PlanView { batches = List.copyOf(batches); }
    }
}
