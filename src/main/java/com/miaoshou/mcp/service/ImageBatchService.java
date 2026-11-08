package com.miaoshou.mcp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.image.ImageAsset;
import com.miaoshou.mcp.domain.image.ImageAssetManifest;
import com.miaoshou.mcp.domain.image.batch.BatchModels.*;
import com.miaoshou.mcp.repository.ImageBatchMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.function.IntFunction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** Local durable scheduling only; no image execution or external side effects. */
@Service
public class ImageBatchService {
    private static final DateTimeFormatter TIME = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final ProductImageService images;
    private final ImageBatchMapper store;
    private final ObjectMapper json;
    private final Clock clock;
    private final int leaseSeconds;
    private final TransactionTemplate transaction;

    public ImageBatchService(ProductImageService images, ImageBatchMapper store, ObjectMapper json, Clock clock,
                             PlatformTransactionManager manager,
                             @Value("${image-batch.lease-seconds:1800}") int leaseSeconds) {
        if (leaseSeconds < 1 || leaseSeconds > 86400) { throw new IllegalArgumentException("Invalid image batch lease duration"); }
        this.images = images; this.store = store; this.json = json; this.clock = clock;
        this.leaseSeconds = leaseSeconds; this.transaction = new TransactionTemplate(manager);
    }

    /** Atomically allocate once per snapshot. Omitted size reuses an existing plan's configuration. */
    public PlanView create(String snapshotId, Integer batchSize) {
        if (batchSize != null && (batchSize < 1 || batchSize > 4)) { fail("BATCH_SIZE_INVALID"); }
        var manifest = images.extract(snapshotId);
        validate(manifest);
        return tx(expired -> {
            String now = now();
            String id = "PLAN-" + UUID.randomUUID();
            int size = batchSize == null ? 4 : batchSize;
            int inserted = store.insertPlan(new PlanRow(id, snapshotId, size, hash(manifest), manifest.totalAssets(), now, now));
            PlanRow plan = store.planBySnapshot(snapshotId);
            if (batchSize != null && plan.batchSize() != batchSize) { fail("BATCH_SIZE_CONFLICT"); }
            if (inserted == 1) {
                for (int i = 0; i < manifest.assets().size(); i++) {
                    int group = i / size;
                    String batchId = id + "-B" + group;
                    if (i % size == 0) {
                        store.insertBatch(new BatchRow(batchId, id, group, BatchStatus.PENDING, now, now));
                    }
                    ImageAsset asset = manifest.assets().get(i);
                    store.insertTask(new TaskRow(id, batchId, asset.assetId(), asset.sequence(), TaskStatus.PENDING,
                            null, 0, null, null, null, null, now, now));
                }
            }
            return view(plan);
        });
    }

    /** Read one consistent plan, recovering expired attempts before returning progress. */
    public PlanView get(String snapshotId, String batchPlanId) {
        return tx(expired -> view(resolve(snapshotId, batchPlanId)));
    }

    /** Persist one batch control action. Repeating pause/cancel is idempotent. */
    public PlanView control(String batchId, String action) {
        return tx(expired -> {
            BatchRow batch = requireBatch(batchId);
            controlBatch(batch, action);
            return view(store.plan(batch.planId()));
        });
    }

    /** Apply pause/resume to remaining eligible batches in a snapshot plan, atomically. */
    public PlanView controlPlan(String snapshotId, String batchPlanId, boolean pause) {
        return tx(expired -> {
            PlanRow plan = resolve(snapshotId, batchPlanId);
            for (var batch : store.batches(plan.id())) {
                if (pause && batch.status() != BatchStatus.COMPLETED && batch.status() != BatchStatus.CANCELLED) {
                    controlBatch(batch, "PAUSE");
                } else if (!pause && batch.status() == BatchStatus.PAUSED) {
                    controlBatch(batch, "RESUME");
                }
            }
            return view(plan);
        });
    }

    /** Explicit retry reuses failed task rows and never requeues completed assets. */
    public PlanView retry(String batchId) {
        return tx(expired -> {
            BatchRow batch = requireBatch(batchId);
            if (batch.status() == BatchStatus.PAUSED || batch.status() == BatchStatus.CANCELLED
                    || batch.status() == BatchStatus.COMPLETED) { fail("BATCH_TRANSITION_INVALID"); }
            for (var task : store.tasks(batchId)) {
                if (task.status() == TaskStatus.FAILED) {
                    store.updateTask(changed(task, TaskStatus.PENDING, null, task.retryCount() + 1, null, null, null, null));
                }
            }
            refresh(batch);
            store.touchPlan(batch.planId(), now());
            return view(store.plan(batch.planId()));
        });
    }

    /** Internal future execution seam: claim exactly one pending task, without executing it. */
    public TaskRow claim(String batchId, String assetId) {
        return tx(expired -> {
            var batch = executable(batchId);
            var task = requireTask(batchId, assetId);
            if (task.status() != TaskStatus.PENDING) { fail("TASK_TRANSITION_INVALID"); }
            var result = changed(task, TaskStatus.PROCESSING, null, task.retryCount(), UUID.randomUUID().toString(),
                    TIME.format(clock.instant().plusSeconds(leaseSeconds)), now(), null);
            store.updateTask(result); refresh(batch); store.touchPlan(batch.planId(), now());
            return result;
        });
    }

    /** Internal lease renewal; an expired or previous attempt cannot extend ownership. */
    public TaskRow heartbeat(String batchId, String assetId, String token) {
        return tx(expired -> {
            var batch = requireBatch(batchId);
            manifest(store.plan(batch.planId()));
            var task = requireTask(batchId, assetId);
            if (batch.status() == BatchStatus.CANCELLED || task.status() != TaskStatus.PROCESSING
                    || token == null || !token.equals(task.attemptToken())) { fail("TASK_ATTEMPT_STALE"); }
            var result = changed(task, task.status(), null, task.retryCount(), token,
                    TIME.format(clock.instant().plusSeconds(leaseSeconds)), task.startedAt(), null);
            store.updateTask(result); store.touchPlan(batch.planId(), now()); return result;
        });
    }

    /** Internal result bookkeeping only. This method does not assert optimization or quality. */
    public TaskRow recordResult(String batchId, String assetId, String token, TaskStatus target) {
        return tx(expired -> {
            if (target != TaskStatus.COMPLETED && target != TaskStatus.FAILED && target != TaskStatus.SKIPPED) {
                fail("TASK_TRANSITION_INVALID");
            }
            var batch = requireBatch(batchId);
            manifest(store.plan(batch.planId()));
            var task = requireTask(batchId, assetId);
            if (batch.status() == BatchStatus.CANCELLED || token == null || !token.equals(task.attemptToken())) {
                fail("TASK_ATTEMPT_STALE");
            }
            if (task.status() == target && !"LEASE_EXPIRED".equals(task.errorCode())) { return task; }
            if (task.status() != TaskStatus.PROCESSING) { fail("TASK_ATTEMPT_STALE"); }
            var result = changed(task, target, target == TaskStatus.FAILED ? "TASK_FAILED" : null,
                    task.retryCount(), token, null, task.startedAt(), now());
            store.updateTask(result); refresh(batch); store.touchPlan(batch.planId(), now()); return result;
        });
    }

    /** Recover expired leases at startup or on demand. Never auto-retry external work. */
    public int recover() { return tx(expired -> expired); }

    private <T> T tx(IntFunction<T> body) {
        try {
            return transaction.execute(status -> {
                String timestamp = now();
                int expired = store.expire(timestamp); // first SQL obtains SQLite writer lock
                if (expired > 0) {
                    for (var batch : store.recoverableBatches()) { refresh(batch); }
                    store.touchExpiredPlans(timestamp);
                }
                return body.apply(expired);
            });
        } catch (DataAccessException | TransactionException exception) {
            throw new BusinessException("BATCH_STORAGE_FAILED", "Image batch transaction failed; retry the same request");
        }
    }

    private void controlBatch(BatchRow batch, String action) {
        BatchStatus status = batch.status();
        switch (action) {
            case "PAUSE" -> {
                if (status == BatchStatus.COMPLETED || status == BatchStatus.CANCELLED) { fail("BATCH_TRANSITION_INVALID"); }
                setBatch(batch, BatchStatus.PAUSED);
            }
            case "RESUME" -> {
                if (status == BatchStatus.PENDING) { return; }
                if (status != BatchStatus.PAUSED) { fail("BATCH_TRANSITION_INVALID"); }
                setBatch(batch, aggregate(store.tasks(batch.id())));
            }
            case "CANCEL" -> {
                if (status == BatchStatus.CANCELLED) { return; }
                if (status == BatchStatus.COMPLETED) { fail("BATCH_TRANSITION_INVALID"); }
                for (var task : store.tasks(batch.id())) {
                    if (task.status() != TaskStatus.COMPLETED && task.status() != TaskStatus.SKIPPED) {
                        store.updateTask(changed(task, TaskStatus.SKIPPED, "BATCH_CANCELLED", task.retryCount(),
                                null, null, task.startedAt(), now()));
                    }
                }
                setBatch(batch, BatchStatus.CANCELLED);
            }
            default -> fail("BATCH_ACTION_INVALID");
        }
        store.touchPlan(batch.planId(), now());
    }

    private PlanRow resolve(String snapshotId, String planId) {
        boolean hasSnapshot = snapshotId != null && !snapshotId.isBlank();
        boolean hasPlan = planId != null && !planId.isBlank();
        if (!hasSnapshot && !hasPlan) { fail("BATCH_PLAN_ID_REQUIRED"); }
        PlanRow plan = hasPlan ? store.plan(planId) : store.planBySnapshot(snapshotId);
        if (plan == null) { fail("BATCH_PLAN_NOT_FOUND"); }
        if (hasSnapshot && !plan.snapshotId().equals(snapshotId)) { fail("BATCH_PLAN_MISMATCH"); }
        return plan;
    }

    private BatchRow requireBatch(String id) {
        var batch = store.batch(id);
        if (batch == null) { fail("BATCH_NOT_FOUND"); }
        return batch;
    }

    private BatchRow executable(String id) {
        var batch = requireBatch(id);
        manifest(store.plan(batch.planId()));
        if (batch.status() == BatchStatus.PAUSED || batch.status() == BatchStatus.CANCELLED
                || batch.status() == BatchStatus.COMPLETED) { fail("BATCH_TRANSITION_INVALID"); }
        return batch;
    }

    private TaskRow requireTask(String batchId, String assetId) {
        return store.tasks(batchId).stream().filter(t -> t.assetId().equals(assetId)).findFirst()
                .orElseThrow(() -> new BusinessException("IMAGE_TASK_NOT_FOUND", "Unknown image task"));
    }

    private TaskRow changed(TaskRow t, TaskStatus status, String error, int retries, String token,
                            String lease, String started, String completed) {
        return new TaskRow(t.planId(), t.batchId(), t.assetId(), t.sequence(), status, error, retries,
                token, lease, started, completed, t.createdAt(), now());
    }

    private void refresh(BatchRow batch) {
        if (batch.status() != BatchStatus.PAUSED && batch.status() != BatchStatus.CANCELLED) {
            var status = aggregate(store.tasks(batch.id()));
            if (status != batch.status()) { setBatch(batch, status); }
        }
    }

    private BatchStatus aggregate(List<TaskRow> tasks) {
        if (tasks.stream().anyMatch(t -> t.status() == TaskStatus.PROCESSING)) { return BatchStatus.PROCESSING; }
        if (tasks.stream().anyMatch(t -> t.status() == TaskStatus.FAILED)) { return BatchStatus.FAILED; }
        if (tasks.stream().anyMatch(t -> t.status() == TaskStatus.PENDING)) { return BatchStatus.PENDING; }
        return BatchStatus.COMPLETED;
    }

    private void setBatch(BatchRow b, BatchStatus status) {
        if (b.status() != status) { store.updateBatch(new BatchRow(b.id(), b.planId(), b.sequence(), status, b.createdAt(), now())); }
    }

    private ImageAssetManifest manifest(PlanRow plan) {
        var manifest = images.extract(plan.snapshotId());
        validate(manifest);
        if (!hash(manifest).equals(plan.manifestHash())) { fail("BATCH_MANIFEST_CHANGED"); }
        return manifest;
    }

    private void validate(ImageAssetManifest manifest) {
        if (!manifest.validation().valid() || manifest.assets().stream().anyMatch(a -> a.status() != ImageAsset.Status.PENDING)) {
            fail("IMAGE_MANIFEST_INVALID");
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < manifest.assets().size(); i++) {
            var asset = manifest.assets().get(i);
            if (!ids.add(asset.assetId()) || asset.sequence() != i) { fail("IMAGE_MANIFEST_INVALID"); }
        }
        if (manifest.totalAssets() != ids.size()) { fail("IMAGE_MANIFEST_INVALID"); }
    }

    private PlanView view(PlanRow plan) {
        var manifest = manifest(plan);
        Map<String, ImageAsset> assets = new LinkedHashMap<>();
        manifest.assets().forEach(a -> assets.put(a.assetId(), a));
        List<BatchView> batches = new ArrayList<>();
        Set<String> assigned = new HashSet<>();
        int pending = 0, processing = 0, completed = 0, failed = 0, skipped = 0, eligible = 0;
        int completedBatches = 0, pausedBatches = 0, cancelledBatches = 0;
        for (var batch : store.batches(plan.id())) {
            List<TaskView> tasks = new ArrayList<>();
            for (var task : store.tasks(batch.id())) {
                var asset = assets.get(task.assetId());
                if (asset == null || !assigned.add(task.assetId()) || task.sequence() != asset.sequence()) { fail("BATCH_PLAN_CORRUPT"); }
                tasks.add(new TaskView(task, asset));
                switch (task.status()) {
                    case PENDING -> { pending++; if (batch.status() != BatchStatus.PAUSED && batch.status() != BatchStatus.CANCELLED) { eligible++; } }
                    case PROCESSING -> processing++;
                    case COMPLETED -> completed++;
                    case FAILED -> failed++;
                    case SKIPPED -> skipped++;
                }
            }
            if (tasks.isEmpty() || tasks.size() > plan.batchSize()) { fail("BATCH_PLAN_CORRUPT"); }
            batches.add(new BatchView(batch, tasks));
            if (batch.status() == BatchStatus.COMPLETED) { completedBatches++; }
            if (batch.status() == BatchStatus.PAUSED) { pausedBatches++; }
            if (batch.status() == BatchStatus.CANCELLED) { cancelledBatches++; }
        }
        if (assigned.size() != plan.totalAssets() || assigned.size() != assets.size()) { fail("BATCH_PLAN_CORRUPT"); }
        var progress = new Progress(assets.size(), assigned.size(), pending, processing, completed, failed, skipped,
                pending + processing + failed, eligible, batches.size(), completedBatches, pausedBatches, cancelledBatches);
        return new PlanView(plan.id(), plan.snapshotId(), plan.batchSize(), assets.size(), batches.size(),
                assigned.size(), skipped, batches, progress, manifest.validation());
    }

    private String hash(ImageAssetManifest manifest) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(manifest).getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new BusinessException("IMAGE_MANIFEST_INVALID", "Cannot digest image manifest");
        }
    }

    private String now() { return TIME.format(clock.instant()); }
    private static void fail(String code) { throw new BusinessException(code, "Image batch request rejected: " + code); }
}
