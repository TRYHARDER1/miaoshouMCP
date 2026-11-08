package com.miaoshou.mcp.service.muse;

import com.miaoshou.mcp.Fixtures;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.SnapshotRow;
import com.miaoshou.mcp.domain.image.muse.MuseModels.*;
import com.miaoshou.mcp.domain.image.processed.MuseProcessedModels.*;
import com.miaoshou.mcp.repository.*;
import com.miaoshou.mcp.service.ImageBatchService;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.ai.mcp.server.enabled=false","spring.datasource.hikari.maximum-pool-size=4"})
class MuseProcessedIntegrationTest {
    static final Path ROOT=root();
    static Path root() { try { return Files.createTempDirectory("miaoshou-processed-"); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",() -> "jdbc:sqlite:"+ROOT.resolve("test.db"));r.add("muse.jobs-root",() -> ROOT.resolve("jobs").toString());
        r.add("muse.delivery-root",() -> ROOT.resolve("delivery").toString());
    }
    @Autowired MuseProcessedImageService service;@Autowired MuseJobService jobs;@Autowired ImageBatchService batches;
    @Autowired SnapshotMapper snapshots;@Autowired MuseProcessedResultMapper store;@Autowired MuseRegisteredResultReader reader;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean OriginalImageFetcher originals;@MockitoBean MuseProcessedImageFetcher fetcher;
    @MockitoSpyBean ProcessedImageValidator validator;
    @BeforeEach void init() throws Exception {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_processed_event");
        when(originals.fetch(any())).thenReturn(new OriginalImageFetcher.ImageBytes(ProcessedImageValidatorTest.image("png",0),"image/png","png",3,4));
        when(fetcher.fetch(any())).thenReturn(new Download(ProcessedImageValidatorTest.image("png",0xff0000),"image/png",200));
        doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return call.callRealMethod();
        }).when(validator).validate(any(),any());
    }
    ExportView batch(int count,int links) throws Exception {
        var e=Fixtures.envelope();var p=(com.fasterxml.jackson.databind.node.ObjectNode)e.path("shopCollectItemInfo");
        var urls=p.putArray("imgUrls");for(int i=0;i<count;i++) { urls.add("https://example.com/original-"+i); }
        p.path("skuPropertyList").forEach(d -> d.path("attrValueList").forEach(v -> ((com.fasterxml.jackson.databind.node.ObjectNode)v).putNull("imgUrl")));
        String sid=UUID.randomUUID().toString();snapshots.insert(new SnapshotRow(sid,17,23,"SG","baseline",e.toString(),Instant.now().toString()));
        String b=batches.create(sid,null).batches().getFirst().batch().id();var exported=jobs.export(b,null);
        List<ResultInput> results=new ArrayList<>();for(int i=0;i<links;i++) { String id=asset(exported,i);results.add(new ResultInput(id,"https://example.com/result/"+id+"?token=PRIVATE")); }
        if(!results.isEmpty()) { jobs.submit(b,results,null); }return exported;
    }
    String asset(ExportView v,int i) { return v.manifest().context().assets().get(i).assetId(); }
    Row row(ExportView v,int i) { return store.find(v.jobId(),asset(v,i)); }
    @Test void validatesCachesAndKeepsRegistrationAndOldStagesUnchanged() throws Exception {
        var v=batch(4,4);var before=legacy();var result=service.download(v.batchId(),null);
        assertThat(result.progress().validated()).isEqualTo(4);
        assertThat(result.results()).allSatisfy(r -> { assertThat(r.integrityVerified()).isTrue();assertThat(r.visualReviewStatus()).isEqualTo("NOT_REVIEWED");assertThat(Path.of(r.processedPath())).exists(); });
        assertThat(result.results()).allSatisfy(r -> assertThat(r.duplicateContent()).isTrue());
        assertThat(result.results().stream().map(View::processedPath)).doesNotHaveDuplicates();
        service.download(v.batchId(),null);verify(fetcher,times(4)).fetch(any());assertThat(legacy()).isEqualTo(before);
        assertThat(service.get(v.batchId(),false).results()).allSatisfy(r -> assertThat(r.integrityVerified()).isFalse());
        assertThat(jobs.results(v.batchId()).results()).allSatisfy(r -> assertThat(r.downloadVerified()).isFalse());
    }
    @Test void partialRegistrationAndCrossBatchInputsNeverCreateMissingTasks() throws Exception {
        var v=batch(4,1);var result=service.download(v.batchId(),null);
        assertThat(result.progress().validated()).isEqualTo(1);assertThat(result.missingAssetIds()).hasSize(3);
        assertThat(result.progress().failed()).isZero();assertThat(row(v,1)).isNull();
        var other=batch(1,1);
        assertThatThrownBy(() -> service.download(v.batchId(),List.of(asset(other,0)))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.download(v.batchId(),List.of())).isInstanceOf(BusinessException.class);
    }
    @Test void perAssetFailuresDoNotRollBackNeighborsAndExpiredLinksAreNotReplaced() throws Exception {
        var v=batch(2,2);when(fetcher.fetch(any())).thenAnswer(i -> {
            Source s=i.getArgument(0);if(s.result().assetId().equals(asset(v,0))) { throw new BusinessException("HTTP_STATUS_403","safe failure"); }
            return new Download(ProcessedImageValidatorTest.image("png",0),"image/png",200);
        });
        var result=service.download(v.batchId(),null);assertThat(result.progress().failed()).isEqualTo(1);assertThat(result.progress().validated()).isEqualTo(1);
        assertThat(row(v,0).httpStatus()).isEqualTo(403);
        service.retry(v.batchId(),asset(v,0),row(v,0).rowVersion());verify(fetcher,times(2)).fetch(any());
        assertThat(row(v,0).retryCount()).isZero();
    }
    @Test void transientFailureRequiresExplicitVersionedRetry() throws Exception {
        var v=batch(1,1);when(fetcher.fetch(any())).thenThrow(new BusinessException("DOWNLOAD_TRANSFER_FAILED","safe"));
        service.download(v.batchId(),null);Row failed=row(v,0);assertThat(failed.status()).isEqualTo(State.FAILED);
        service.download(v.batchId(),null);verify(fetcher,times(1)).fetch(any());
        doReturn(new Download(ProcessedImageValidatorTest.image("png",0),"image/png",200)).when(fetcher).fetch(any());
        service.retry(v.batchId(),asset(v,0),failed.rowVersion());assertThat(row(v,0).status()).isEqualTo(State.VALIDATED);assertThat(row(v,0).retryCount()).isEqualTo(1);
        service.retry(v.batchId(),asset(v,0),failed.rowVersion());verify(fetcher,times(2)).fetch(any());
    }
    @Test void publicationCommitFailureRecoversWithoutAnotherDownload() throws Exception {
        var v=batch(1,1);trigger("VALIDATED");service.download(v.batchId(),null);
        assertThat(row(v,0).status()).isEqualTo(State.FAILED);jdbc.execute("DROP TRIGGER fail_processed_event");
        assertThat(service.recover(v.batchId(),null).progress().validated()).isEqualTo(1);verify(fetcher,times(1)).fetch(any());
        assertThat(row(v,0).attemptNo()).isEqualTo(1);
    }
    @Test void completedLocalDownloadSurvivesStateFailureAndGetsFreshRecoveryToken() throws Exception {
        var v=batch(1,1);trigger("DOWNLOADED");service.download(v.batchId(),null);String old=row(v,0).attemptToken();
        jdbc.execute("DROP TRIGGER fail_processed_event");var result=service.recover(v.batchId(),null);
        assertThat(result.progress().validated()).isEqualTo(1);assertThat(row(v,0).attemptToken()).isNotEqualTo(old);assertThat(row(v,0).attemptNo()).isEqualTo(2);
        assertThat(row(v,0).retryCount()).isZero();verify(fetcher,times(1)).fetch(any());
    }
    @Test void corruptPublishedFileIsRecordedAndNeverOverwritten() throws Exception {
        var v=batch(1,1);var result=service.download(v.batchId(),null);Path file=Path.of(result.results().getFirst().processedPath());
        Files.write(file,new byte[]{1,2,3});assertThat(service.get(v.batchId(),true).progress().failed()).isEqualTo(1);
        service.download(v.batchId(),null);service.retry(v.batchId(),asset(v,0),row(v,0).rowVersion());
        assertThat(Files.readAllBytes(file)).containsExactly((byte)1,(byte)2,(byte)3);verify(fetcher,times(1)).fetch(any());
    }
    @Test void busyReplayDoesNotFailOwnerAndLateOldWorkerCannotOverwriteReplacement() throws Exception {
        var v=batch(1,1);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        byte[] newer=ProcessedImageValidatorTest.image("png",0xff0000);
        when(fetcher.fetch(any())).thenAnswer(i -> {
            if(calls.incrementAndGet()==1) { entered.countDown();assertThat(release.await(20,TimeUnit.SECONDS)).isTrue();return new Download(ProcessedImageValidatorTest.image("png",0),"image/png",200); }
            return new Download(newer,"image/png",200);
        });
        try(var pool=Executors.newSingleThreadExecutor()) {
            var old=pool.submit(() -> service.download(v.batchId(),null));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            try {
                service.download(v.batchId(),null);assertThat(row(v,0).status()).isEqualTo(State.DOWNLOADING);
                jdbc.update("UPDATE ms_muse_processed_result SET lease_until='2000-01-01T00:00:00.000Z' WHERE job_id=?",v.jobId());service.expire();
                var latest=service.retry(v.batchId(),asset(v,0),row(v,0).rowVersion());assertThat(latest.progress().validated()).isEqualTo(1);
            } finally { release.countDown(); }
            old.get();
        }
        assertThat(row(v,0).status()).isEqualTo(State.VALIDATED);assertThat(row(v,0).processedSha256()).isEqualTo(ProcessedImageValidator.sha(newer));
    }
    @Test void invalidImagesAndSqlConstraintsCannotBecomeValidated() throws Exception {
        var v=batch(1,1);when(fetcher.fetch(any())).thenReturn(new Download("<html>bad</html>".getBytes(),"image/png",200));
        assertThat(service.download(v.batchId(),null).progress().failed()).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE ms_muse_processed_result SET status='VALIDATED' WHERE job_id=?",v.jobId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ms_muse_processed_event WHERE job_id=?",Integer.class,v.jobId())).isGreaterThanOrEqualTo(3);
    }
    @Test void pausedBatchBlocksNetworkButHistoricalQueriesRemainAvailable() throws Exception {
        var v=batch(1,1);batches.control(v.batchId(),"PAUSE");
        assertThatThrownBy(() -> service.download(v.batchId(),null)).isInstanceOf(BusinessException.class);
        assertThat(service.get(v.batchId(),false).progress().notStartedCount()).isEqualTo(1);verifyNoInteractions(fetcher);
    }
    @Test void otherProcessLeasesAlsoEnforceDatabaseCapacity() throws Exception {
        var v=batch(4,4);
        for(int i=0;i<2;i++) {
            Source source=reader.read(v.batchId(),asset(v,i),true);store.insert(source,"2026-11-08T00:00:00.000Z");Row r=row(v,i);
            store.claim(r,State.DOWNLOADING,UUID.randomUUID().toString(),"2099-01-01T00:00:00.000Z",null,0,true,"2026-11-08T00:00:00.000Z");
        }
        try {
            var result=service.download(v.batchId(),List.of(asset(v,2)));
            assertThat(result.results().stream().filter(r -> r.assetId().equals(asset(v,2))).findFirst().orElseThrow().errorCode()).isEqualTo("DOWNLOAD_CAPACITY_BUSY");
            verifyNoInteractions(fetcher);
        } finally {
            jdbc.update("UPDATE ms_muse_processed_result SET status='FAILED',lease_until=NULL WHERE job_id=?",v.jobId());
        }
    }
    void trigger(String state) {
        jdbc.execute("CREATE TRIGGER fail_processed_event BEFORE INSERT ON ms_muse_processed_event WHEN NEW.to_status='"+state+"' BEGIN SELECT RAISE(ABORT,'fixture'); END");
    }
    List<Object> legacy() {
        List<Object> result=new ArrayList<>();for(String table:List.of("ms_product_snapshot","ms_product_draft","ms_ai_generation","ms_image_batch_plan","ms_image_batch","ms_image_batch_task","ms_muse_job","ms_muse_result")) {
            result.add(jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1,2"));
        }return result;
    }
}
