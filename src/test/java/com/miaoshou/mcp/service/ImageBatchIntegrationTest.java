package com.miaoshou.mcp.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miaoshou.mcp.Fixtures;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.SnapshotRow;
import com.miaoshou.mcp.domain.image.batch.BatchModels.*;
import com.miaoshou.mcp.repository.SnapshotMapper;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.ai.mcp.server.enabled=false", "spring.datasource.hikari.maximum-pool-size=4", "image-batch.lease-seconds=30"})
@Import(ImageBatchIntegrationTest.TimeConfig.class)
class ImageBatchIntegrationTest {
    static final AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-11-08T10:00:00Z"));
    @TestConfiguration
    static class TimeConfig {
        @Bean @Primary Clock batchTestClock() {
            return new Clock() {
                public ZoneId getZone() { return ZoneOffset.UTC; }
                public Clock withZone(ZoneId zone) { return this; }
                public Instant instant() { return time.get(); }
            };
        }
    }
    static final String DATABASE = database();
    static String database() {
        try { return "jdbc:sqlite:" + Files.createTempDirectory("miaoshou-batches-").resolve("test.db"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) { r.add("spring.datasource.url", () -> DATABASE); }

    @Autowired ImageBatchService service;
    @Autowired ProductImageService images;
    @Autowired SnapshotMapper snapshots;
    @Autowired JdbcTemplate jdbc;
    @Autowired ToolCallbackProvider tools;

    @BeforeEach
    void setup() {
        time.set(Instant.parse("2026-11-08T10:00:00Z"));
        jdbc.execute("DROP TRIGGER IF EXISTS fail_batch_task");
        jdbc.execute("DROP TRIGGER IF EXISTS fail_batch_update");
        jdbc.update("DELETE FROM ms_image_batch_task");
        jdbc.update("DELETE FROM ms_image_batch");
        jdbc.update("DELETE FROM ms_image_batch_plan");
    }

    String snapshot(int count) throws Exception {
        ObjectNode product = Fixtures.product();
        product.put("notes", "<p>text</p>");
        var urls = product.putArray("imgUrls");
        for (int i=0; i<count; i++) { urls.add("https://example.com/image-"+i+".jpg"); }
        product.path("skuPropertyList").forEach(d -> d.path("attrValueList").forEach(v -> ((ObjectNode)v).putNull("imgUrl")));
        return save(product);
    }

    String save(ObjectNode product) throws Exception {
        var envelope = Fixtures.envelope(); envelope.set("shopCollectItemInfo", product);
        String id=UUID.randomUUID().toString();
        snapshots.insert(new SnapshotRow(id,17,23,"SG","baseline",envelope.toString(),time.get().toString()));
        return id;
    }

    String batch(PlanView p) { return p.batches().getFirst().batch().id(); }
    String asset(PlanView p, int index) { return p.batches().getFirst().tasks().get(index).asset().assetId(); }
    TaskRow task(PlanView p, int index) { return p.batches().getFirst().tasks().get(index).task(); }
    void code(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException)e).code()).isEqualTo(code);
    }

    @ParameterizedTest
    @CsvSource({"0,0","1,1","4,1","5,2","8,2","10,3"})
    void T01to05and10_groupingWithoutDuplication(int count,int groups) throws Exception {
        String sid=snapshot(count); var p=service.create(sid,null);
        assertThat(p.totalBatches()).isEqualTo(groups);
        assertThat(p.totalAssigned()).isEqualTo(count);
        assertThat(p.progress().pending()).isEqualTo(count);
        assertThat(p.batches()).allSatisfy(b -> assertThat(b.tasks().size()).isBetween(1,4));
        assertThat(p.batches().stream().flatMap(b -> b.tasks().stream()).map(t -> t.asset().sequence()).toList())
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0,count).boxed().toList());
        assertThat(service.create(sid,null)).isEqualTo(p);
    }

    @Test
    void T06and09_sharedSkuReferencesAndUnclassifiedPreserved() throws Exception {
        String sid=save(Fixtures.product()); var original=images.extract(sid); var p=service.create(sid,4);
        assertThat(p.totalAssigned()).isEqualTo(2);
        assertThat(p.batches().getFirst().tasks().stream().map(TaskView::asset).toList()).isEqualTo(original.assets());
        assertThat(p.batches().getFirst().tasks().getFirst().asset().references()).hasSize(2);
        assertThat(p.batches().getFirst().tasks().getFirst().asset().references().getLast().skuMapKeys()).hasSize(2);
    }

    @Test
    void T07_idempotentFrozenConfigurationAndOptionalIdentifiers() throws Exception {
        String sid=snapshot(5); var p=service.create(sid,2);
        assertThat(service.create(sid,null)).isEqualTo(p);
        assertThat(service.create(sid,2)).isEqualTo(p);
        assertThat(service.get(null,p.batchPlanId())).isEqualTo(p);
        code(() -> service.create(sid,4),"BATCH_SIZE_CONFLICT");
        code(() -> service.create(sid,0),"BATCH_SIZE_INVALID");
        code(() -> service.create(sid,5),"BATCH_SIZE_INVALID");
        code(() -> service.get("other",p.batchPlanId()),"BATCH_PLAN_MISMATCH");
        code(() -> service.get(null,null),"BATCH_PLAN_ID_REQUIRED");
    }

    @Test
    void T08_invalidUrlsAndStructuralErrorsCreateNothing() throws Exception {
        var product=Fixtures.product(); product.putArray("imgUrls").add("javascript:bad");
        String sid=save(product);
        code(() -> service.create(sid,null),"IMAGE_MANIFEST_INVALID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ms_image_batch_plan WHERE snapshot_id=?",Integer.class,sid)).isZero();
        product.put("notes",42); String broken=save(product);
        code(() -> service.create(broken,null),"IMAGE_MANIFEST_INVALID");
    }

    @Test
    void T11and12_pauseResumeKeepsPartialProgressAndAllAssignments() throws Exception {
        String sid=snapshot(5); var p=service.create(sid,null); String b=batch(p),a=asset(p,0);
        var claimed=service.claim(b,a); service.recordResult(b,a,claimed.attemptToken(),TaskStatus.COMPLETED);
        var paused=service.control(b,"PAUSE");
        assertThat(paused.progress().completed()).isEqualTo(1);
        assertThat(paused.progress().eligiblePending()).isEqualTo(1);
        code(() -> service.claim(b,asset(p,1)),"BATCH_TRANSITION_INVALID");
        service.control(b,"PAUSE"); var resumed=service.control(b,"RESUME");
        assertThat(task(resumed,0).status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(resumed.totalAssigned()).isEqualTo(5);
        service.controlPlan(sid,null,true);
        assertThat(service.get(sid,null).progress().eligiblePending()).isZero();
        assertThat(service.controlPlan(null,p.batchPlanId(),false).progress().eligiblePending()).isEqualTo(4);
    }

    @Test
    void T13_persistedStateDoesNotDependOnMapperCache() throws Exception {
        String sid=snapshot(5); var p=service.create(sid,null); service.control(batch(p),"PAUSE");
        assertThat(jdbc.queryForObject("SELECT status FROM ms_image_batch WHERE id=?",String.class,batch(p))).isEqualTo("PAUSED");
        assertThat(service.get(sid,null).batches().getFirst().batch().status()).isEqualTo(BatchStatus.PAUSED);
    }

    @Test
    void T14_failOneRetryOnlyFailedAndFencePreviousAttempt() throws Exception {
        var p=service.create(snapshot(4),null); String b=batch(p);
        var first=service.claim(b,asset(p,0)); var failed=service.claim(b,asset(p,1));
        service.recordResult(b,first.assetId(),first.attemptToken(),TaskStatus.COMPLETED);
        service.recordResult(b,failed.assetId(),failed.attemptToken(),TaskStatus.FAILED);
        var state=service.get(null,p.batchPlanId());
        assertThat(state.progress().completed()).isEqualTo(1);
        assertThat(state.progress().failed()).isEqualTo(1);
        var retried=service.retry(b);
        assertThat(task(retried,0).status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task(retried,1).retryCount()).isEqualTo(1);
        assertThat(task(retried,2).retryCount()).isZero();
        var next=service.claim(b,failed.assetId());
        code(() -> service.recordResult(b,failed.assetId(),failed.attemptToken(),TaskStatus.COMPLETED),"TASK_ATTEMPT_STALE");
        service.recordResult(b,next.assetId(),next.attemptToken(),TaskStatus.COMPLETED);
    }

    @Test
    void T15_transactionFailureRollsBackWholePlanThenSameRequestSucceeds() throws Exception {
        String sid=snapshot(10);
        jdbc.execute("CREATE TRIGGER fail_batch_task BEFORE INSERT ON ms_image_batch_task WHEN NEW.sequence=4 BEGIN SELECT RAISE(ABORT,'test failure'); END");
        try {
            code(() -> service.create(sid,null),"BATCH_STORAGE_FAILED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ms_image_batch_plan WHERE snapshot_id=?",Integer.class,sid)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ms_image_batch_task t LEFT JOIN ms_image_batch_plan p ON p.id=t.plan_id WHERE p.id IS NULL",Integer.class)).isZero();
        } finally { jdbc.execute("DROP TRIGGER fail_batch_task"); }
        assertThat(service.create(sid,null).totalAssigned()).isEqualTo(10);
    }

    @Test
    void T16_uniqueAndCompositeForeignKeysProtectAllocation() throws Exception {
        var p=service.create(snapshot(5),null); var t=task(p,0);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ms_image_batch_task(plan_id,batch_id,asset_id,sequence,status,created_at,updated_at) VALUES(?,?,?,99,'PENDING','x','x')",
                p.batchPlanId(),p.batches().getLast().batch().id(),t.assetId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var other=service.create(snapshot(1),null);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ms_image_batch_task(plan_id,batch_id,asset_id,sequence,status,created_at,updated_at) VALUES(?,?,?,99,'PENDING','x','x')",
                p.batchPlanId(),batch(other),"another")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void T17_cancelPreservesCompletedAndInvalidatesActiveAttempt() throws Exception {
        var p=service.create(snapshot(4),null); String b=batch(p);
        var done=service.claim(b,asset(p,0)); service.recordResult(b,done.assetId(),done.attemptToken(),TaskStatus.COMPLETED);
        var active=service.claim(b,asset(p,1)); var cancelled=service.control(b,"CANCEL");
        assertThat(cancelled.progress().completed()).isEqualTo(1);
        assertThat(cancelled.progress().skipped()).isEqualTo(3);
        assertThat(cancelled.progress().remaining()).isZero();
        assertThat(service.control(b,"CANCEL")).isEqualTo(cancelled);
        code(() -> service.control(b,"RESUME"),"BATCH_TRANSITION_INVALID");
        code(() -> service.claim(b,asset(p,2)),"BATCH_TRANSITION_INVALID");
        code(() -> service.recordResult(b,active.assetId(),active.attemptToken(),TaskStatus.COMPLETED),"TASK_ATTEMPT_STALE");
    }

    @Test
    void T18_illegalTransitionsAndDuplicateResults() throws Exception {
        var p=service.create(snapshot(1),null); String b=batch(p), a=asset(p,0);
        var attempt=service.claim(b,a);
        code(() -> service.claim(b,a),"TASK_TRANSITION_INVALID");
        code(() -> service.recordResult(b,a,attempt.attemptToken(),TaskStatus.PENDING),"TASK_TRANSITION_INVALID");
        var done=service.recordResult(b,a,attempt.attemptToken(),TaskStatus.COMPLETED);
        assertThat(service.recordResult(b,a,attempt.attemptToken(),TaskStatus.COMPLETED)).isEqualTo(done);
        assertThat(service.get(null,p.batchPlanId()).progress().completedBatches()).isEqualTo(1);
        code(() -> service.control(b,"PAUSE"),"BATCH_TRANSITION_INVALID");
        code(() -> service.control(b,"CANCEL"),"BATCH_TRANSITION_INVALID");
    }

    @Test
    void T19_expiredAttemptsRequireRetryAndHeartbeatExtendsLease() throws Exception {
        var p=service.create(snapshot(2),null); String b=batch(p);
        var a=service.claim(b,asset(p,0));
        time.set(time.get().plusSeconds(20)); service.heartbeat(b,a.assetId(),a.attemptToken());
        time.set(time.get().plusSeconds(15)); assertThat(service.recover()).isZero();
        time.set(time.get().plusSeconds(16)); assertThat(service.recover()).isEqualTo(1);
        var state=service.get(null,p.batchPlanId());
        assertThat(task(state,0).status()).isEqualTo(TaskStatus.FAILED);
        assertThat(task(state,0).errorCode()).isEqualTo("LEASE_EXPIRED");
        assertThat(task(state,0).retryCount()).isZero();
        code(() -> service.recordResult(b,a.assetId(),a.attemptToken(),TaskStatus.COMPLETED),"TASK_ATTEMPT_STALE");
        service.retry(b); assertThat(service.claim(b,a.assetId()).attemptToken()).isNotEqualTo(a.attemptToken());
    }

    @Test
    void T20_concurrentSameSnapshotAndMultipleProductsHaveOneAllocationEach() throws Exception {
        String one=snapshot(10),two=snapshot(5);
        try (var executor=Executors.newFixedThreadPool(8)) {
            var jobs=new ArrayList<java.util.concurrent.Future<PlanView>>();
            for (int i=0;i<12;i++) { String sid=i%3==0?two:one; jobs.add(executor.submit(() -> service.create(sid,null))); }
            Set<String> ids=new HashSet<>(); for (var job:jobs) { ids.add(job.get().batchPlanId()); }
            assertThat(ids).hasSize(2);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ms_image_batch_plan WHERE snapshot_id IN (?,?)",Integer.class,one,two)).isEqualTo(2);
        assertThat(service.get(one,null).totalAssigned()).isEqualTo(10);
        assertThat(service.get(two,null).totalAssigned()).isEqualTo(5);
        assertThat(service.get(one,null).batches().getFirst().tasks().getFirst().asset().assetId())
                .isNotEqualTo(service.get(two,null).batches().getFirst().tasks().getFirst().asset().assetId());
    }

    @Test
    void databaseUpdateFailureRollsBackStateAndReferencesRemainIntact() throws Exception {
        String sid=save(Fixtures.product()); var original=snapshots.find(sid).originalJson(); var p=service.create(sid,null);
        String b=batch(p); var attempt=service.claim(b,asset(p,0));
        jdbc.execute("CREATE TRIGGER fail_batch_update BEFORE UPDATE ON ms_image_batch_task WHEN NEW.status='COMPLETED' BEGIN SELECT RAISE(ABORT,'private failure text'); END");
        try { code(() -> service.recordResult(b,attempt.assetId(),attempt.attemptToken(),TaskStatus.COMPLETED),"BATCH_STORAGE_FAILED"); }
        finally { jdbc.execute("DROP TRIGGER fail_batch_update"); }
        assertThat(task(service.get(sid,null),0).status()).isEqualTo(TaskStatus.PROCESSING);
        assertThat(snapshots.find(sid).originalJson()).isEqualTo(original);
        assertThat(service.get(sid,null).batches().getFirst().tasks().stream().map(TaskView::asset).toList()).isEqualTo(images.extract(sid).assets());
    }

    @Test
    void manifestChangesAreRejectedAndAllToolsRegistered() throws Exception {
        String sid=snapshot(1); var p=service.create(sid,null);
        jdbc.update("UPDATE ms_image_batch_plan SET manifest_hash='bad' WHERE id=?",p.batchPlanId());
        code(() -> service.get(sid,null),"BATCH_MANIFEST_CHANGED");
        assertThat(tools.getToolCallbacks()).hasSize(34);
        assertThat(Arrays.stream(tools.getToolCallbacks()).map(t -> t.getToolDefinition().name()))
                .contains("product_image_create_batches","product_image_get_batches","product_image_recover_batches");
    }
}
