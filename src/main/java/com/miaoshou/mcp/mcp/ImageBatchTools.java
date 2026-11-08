package com.miaoshou.mcp.mcp;

import com.miaoshou.mcp.domain.image.batch.BatchModels.PlanView;
import com.miaoshou.mcp.domain.image.batch.BatchModels.Progress;
import com.miaoshou.mcp.service.ImageBatchService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** MCP scheduling controls; actual image execution is intentionally not exposed. */
@Component
public class ImageBatchTools {
    private final ImageBatchService batches;
    public ImageBatchTools(ImageBatchService batches) { this.batches = batches; }

    /** Create once or return persisted progress, without dispatching images. */
    @Tool(name="product_image_create_batches", description="Idempotently allocate existing snapshot assets into durable batches of 1–4. Omitted batchSize defaults to 4 for a new plan, reuses existing configuration otherwise. No image processing.")
    public PlanView create(String snapshotId, @ToolParam(required=false) Integer batchSize) { return batches.create(snapshotId, batchSize); }

    /** Resolve by either identifier, checking consistency when both are present. */
    @Tool(name="product_image_get_batches", description="Read persisted batches and complete M5.1 asset references. Supply snapshotId or batchPlanId. Recovers expired leases to FAILED, without retrying.")
    public PlanView get(@ToolParam(required=false) String snapshotId, @ToolParam(required=false) String batchPlanId) { return batches.get(snapshotId, batchPlanId); }

    /** Return task-state counts, including paused and remaining work. */
    @Tool(name="product_image_get_batch_progress", description="Read consistent durable progress by snapshotId or batchPlanId. COMPLETED is scheduling state, not proof of Muse optimization.")
    public Progress progress(@ToolParam(required=false) String snapshotId, @ToolParam(required=false) String batchPlanId) { return batches.get(snapshotId, batchPlanId).progress(); }

    /** Pause new claims while retaining active and completed work. */
    @Tool(name="product_image_pause_batch", description="Pause one existing batch without losing assigned assets or progress.")
    public PlanView pause(String batchId) { return batches.control(batchId, "PAUSE"); }

    /** Restore the existing batch, without reallocating anything. */
    @Tool(name="product_image_resume_batch", description="Resume a paused batch from persisted task states; creates no new tasks.")
    public PlanView resume(String batchId) { return batches.control(batchId, "RESUME"); }

    /** Cancel permanently; preserve completed work and skip unfinished tasks. */
    @Tool(name="product_image_cancel_batch", description="Permanently cancel a batch, preserving completed tasks and marking other tasks SKIPPED. Cannot resume a cancelled batch.")
    public PlanView cancel(String batchId) { return batches.control(batchId, "CANCEL"); }

    /** Pause all remaining batches in a snapshot's plan atomically. */
    @Tool(name="product_image_pause_batches", description="Atomically pause all remaining batches in one snapshot plan. Supply snapshotId or batchPlanId.")
    public PlanView pauseAll(@ToolParam(required=false) String snapshotId, @ToolParam(required=false) String batchPlanId) { return batches.controlPlan(snapshotId, batchPlanId, true); }

    /** Resume only paused batches in the same persisted plan. */
    @Tool(name="product_image_resume_batches", description="Resume paused batches in one snapshot plan, keeping completed and cancelled work intact.")
    public PlanView resumeAll(@ToolParam(required=false) String snapshotId, @ToolParam(required=false) String batchPlanId) { return batches.controlPlan(snapshotId, batchPlanId, false); }

    /** Explicit retry never changes asset assignment. */
    @Tool(name="product_image_retry_batch", description="Explicitly reset FAILED tasks to PENDING in the original batch and increment retry counts. Preserves completed tasks. Check external results before retrying future external work.")
    public PlanView retry(String batchId) { return batches.retry(batchId); }

    /** On-demand lease recovery preserves active attempts and does not auto-dispatch. */
    @Tool(name="product_image_recover_batches", description="Recover expired PROCESSING tasks to FAILED/LEASE_EXPIRED and return the existing plan. Active leases remain unchanged; explicit retry required.")
    public PlanView recover(@ToolParam(required=false) String snapshotId, @ToolParam(required=false) String batchPlanId) { return batches.get(snapshotId, batchPlanId); }
}
