package com.miaoshou.mcp.service.muse;

import com.miaoshou.mcp.Fixtures;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.SnapshotRow;
import com.miaoshou.mcp.domain.image.muse.MuseModels.*;
import com.miaoshou.mcp.repository.SnapshotMapper;
import com.miaoshou.mcp.repository.MuseJobMapper;
import com.miaoshou.mcp.service.ImageBatchService;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.ai.mcp.server.enabled=false","spring.datasource.hikari.maximum-pool-size=4"})
class MuseJobIntegrationTest {
    static final Path ROOT=root();
    static Path root() { try { return Files.createTempDirectory("miaoshou-muse-"); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",() -> "jdbc:sqlite:"+ROOT.resolve("test.db"));
        r.add("muse.jobs-root",() -> ROOT.resolve("jobs").toString());
    }
    @Autowired MuseJobService jobs;
    @Autowired ImageBatchService batches;
    @Autowired SnapshotMapper snapshots;
    @Autowired MuseJobMapper store;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean OriginalImageFetcher fetcher;

    @BeforeEach void setup() throws Exception {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_job_publish"); jdbc.execute("DROP TRIGGER IF EXISTS fail_result");
        jdbc.update("DELETE FROM ms_muse_result"); jdbc.update("DELETE FROM ms_muse_job");
        jdbc.update("DELETE FROM ms_image_batch_task"); jdbc.update("DELETE FROM ms_image_batch"); jdbc.update("DELETE FROM ms_image_batch_plan");
        when(fetcher.fetch(any())).thenReturn(new OriginalImageFetcher.ImageBytes(OriginalImageSecurityTest.png(),"image/png","png",2,3));
    }
    String batch(int images) throws Exception {
        var envelope=Fixtures.envelope(); var product=(com.fasterxml.jackson.databind.node.ObjectNode)envelope.path("shopCollectItemInfo");
        var urls=product.putArray("imgUrls"); for(int i=0;i<images;i++) { urls.add("https://example.com/photo-"+i+".jpg"); }
        product.path("skuPropertyList").forEach(d -> d.path("attrValueList").forEach(v -> ((com.fasterxml.jackson.databind.node.ObjectNode)v).putNull("imgUrl")));
        return create(envelope);
    }
    String create(com.fasterxml.jackson.databind.node.ObjectNode envelope) {
        String id=UUID.randomUUID().toString();snapshots.insert(new SnapshotRow(id,17,23,"SG","baseline",envelope.toString(),Instant.now().toString()));
        return batches.create(id,null).batches().getFirst().batch().id();
    }
    String id(ExportView view,int i) { return view.manifest().context().assets().get(i).assetId(); }
    ResultInput link(ExportView v,int i) { return new ResultInput(id(v,i),"https://results.example.com/"+id(v,i)+".jpg"); }
    void code(Runnable r,String code) {
        assertThatThrownBy(r::run).isInstanceOf(BusinessException.class).extracting(e -> ((BusinessException)e).code()).isEqualTo(code);
    }
    @ParameterizedTest @ValueSource(ints={1,2,3,4})
    void T01to03_completeOriginalFilesAndIdempotentExport(int count) throws Exception {
        String b=batch(count); var v=jobs.export(b,null);
        assertThat(v.imageCount()).isEqualTo(count); assertThat(v.status()).isEqualTo(HandoffStatus.EXPORTED);
        Path directory=Path.of(v.exportPath()); assertThat(directory.resolve("job.json")).exists();
        for(var f:v.manifest().originals()) {
            assertThat(directory.resolve("batches/BATCH-"+b+"/original/"+f.fileName())).hasBinaryContent(OriginalImageSecurityTest.png());
        }
        assertThat(jobs.export(b,1)).isEqualTo(v); verify(fetcher,times(count)).fetch(any());
        assertThat(v.prompt()).contains("Singapore / English","Independent output",id(v,0));
    }
    @Test void T04to06_allSkuReferencesAndCandidateRolesPreserved() throws Exception {
        var envelope=Fixtures.envelope(); String b=create(envelope); var v=jobs.export(b,null);
        assertThat(v.imageCount()).isEqualTo(2);
        assertThat(v.manifest().context().assets().getFirst().references()).hasSize(2);
        assertThat(v.manifest().context().assets().getFirst().references().getLast().skuMapKeys()).hasSize(2);
        assertThat(v.prompt()).contains("MAIN_IMAGE_CANDIDATE","UNCLASSIFIED","SKU_IMAGE",";a;b;","not designate");
    }
    @Test void T09partialThenRemainingAndManualHandoff() throws Exception {
        String b=batch(4); var v=jobs.export(b,null);
        assertThat(jobs.handedOff(b,null).status()).isEqualTo(HandoffStatus.WAITING_MUSE);
        var partial=jobs.submit(b,List.of(link(v,0)),null);
        assertThat(partial.status()).isEqualTo(HandoffStatus.PARTIALLY_RECEIVED); assertThat(partial.missingAssetIds()).hasSize(3);
        assertThat(jobs.submit(b,List.of(link(v,0)),1)).isEqualTo(partial);
        var rest=jobs.submit(b,List.of(link(v,1),link(v,2),link(v,3)),null);
        assertThat(rest.status()).isEqualTo(HandoffStatus.LINKS_RECEIVED); assertThat(rest.missingAssetIds()).isEmpty();
        assertThat(rest.results()).allSatisfy(r -> { assertThat(r.status()).isEqualTo("LINK_REGISTERED");assertThat(r.downloadVerified()).isFalse(); });
        verify(fetcher,times(4)).fetch(any()); // result registration never uses downloader
    }
    @Test void T10fourResultsAndT11tooManyRejectedAtomically() throws Exception {
        String b=batch(4); var v=jobs.export(b,null);
        var four=java.util.stream.IntStream.range(0,4).mapToObj(i -> link(v,i)).toList();
        var five=new ArrayList<>(four); five.add(link(v,0));
        code(() -> jobs.submit(b,five,null),"MUSE_RESULT_COUNT_INVALID"); assertThat(jobs.results(b).results()).isEmpty();
        assertThat(jobs.submit(b,four,null).results()).hasSize(4);
    }
    @Test void T12crossBatchAndT14conflictsNeverPartiallyCommit() throws Exception {
        var one=jobs.export(batch(2),null);var two=jobs.export(batch(1),null);
        code(() -> jobs.submit(one.batchId(),List.of(link(one,0),link(two,0)),null),"MUSE_ASSET_NOT_IN_BATCH");
        assertThat(jobs.results(one.batchId()).results()).isEmpty();
        jobs.submit(one.batchId(),List.of(link(one,0)),null);
        code(() -> jobs.submit(one.batchId(),List.of(new ResultInput(id(one,0),"https://results.example.com/other.jpg")),null),"MUSE_RESULT_CONFLICT");
        code(() -> jobs.submit(one.batchId(),List.of(new ResultInput(id(one,1),link(one,0).downloadUrl())),null),"MUSE_RESULT_URL_CONFLICT");
        code(() -> jobs.submit(two.batchId(),List.of(new ResultInput(id(two,0),link(one,0).downloadUrl())),null),"MUSE_RESULT_URL_CONFLICT");
    }
    @Test void T15and16unsafeOrEmptyLinksAndT17versionConflict() throws Exception {
        var v=jobs.export(batch(1),null);
        for(String url:List.of("","not a URL","http://example.com/a","https://127.0.0.1/a")) {
            code(() -> jobs.submit(v.batchId(),List.of(new ResultInput(id(v,0),url)),null),"IMAGE_URL_REJECTED");
        }
        code(() -> jobs.export(v.batchId(),2),"MUSE_JOB_VERSION_CONFLICT");
        code(() -> jobs.submit(v.batchId(),List.of(link(v,0)),2),"MUSE_JOB_VERSION_CONFLICT");
        assertThat(jobs.results(v.batchId()).results()).isEmpty();
    }
    @Test void T20allM52RowsAndOriginalSnapshotUnchangedEvenWithExpiredLease() throws Exception {
        var v=jobs.export(batch(2),null); String other=batch(1);
        jdbc.update("UPDATE ms_image_batch_task SET status='PROCESSING',attempt_token='test',lease_until='2000-01-01T00:00:00.000Z' WHERE batch_id=?",other);
        var before=jdbc.queryForList("SELECT * FROM ms_image_batch_task ORDER BY asset_id");
        var batchBefore=jdbc.queryForList("SELECT * FROM ms_image_batch ORDER BY id");
        var planBefore=jdbc.queryForList("SELECT * FROM ms_image_batch_plan ORDER BY id");
        var originals=jdbc.queryForList("SELECT * FROM ms_product_snapshot ORDER BY id");
        jobs.handedOff(v.batchId(),null);jobs.submit(v.batchId(),List.of(link(v,0)),null);jobs.results(v.batchId());jobs.export(v.batchId(),null);
        assertThat(jdbc.queryForList("SELECT * FROM ms_image_batch_task ORDER BY asset_id")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM ms_image_batch ORDER BY id")).isEqualTo(batchBefore);
        assertThat(jdbc.queryForList("SELECT * FROM ms_image_batch_plan ORDER BY id")).isEqualTo(planBefore);
        assertThat(jdbc.queryForList("SELECT * FROM ms_product_snapshot ORDER BY id")).isEqualTo(originals);
    }
    @Test void publishedDirectorySurvivesDatabaseCommitFailureAndIsReconciled() throws Exception {
        String b=batch(2);
        jdbc.execute("CREATE TRIGGER fail_job_publish BEFORE UPDATE ON ms_muse_job WHEN NEW.status='EXPORTED' BEGIN SELECT RAISE(ABORT,'fixture'); END");
        code(() -> jobs.export(b,null),"MUSE_STORAGE_FAILED");
        jdbc.execute("DROP TRIGGER fail_job_publish");
        var recovered=jobs.export(b,null); assertThat(recovered.status()).isEqualTo(HandoffStatus.EXPORTED);
        verify(fetcher,times(2)).fetch(any());
    }
    @Test void activeExportIsFencedAndConcurrentDuplicateDoesNotDownloadAgain() throws Exception {
        String b=batch(1); CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(fetcher.fetch(any())).thenAnswer(i -> { entered.countDown(); assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();
            return new OriginalImageFetcher.ImageBytes(OriginalImageSecurityTest.png(),"image/png","png",2,3); });
        try(var pool=Executors.newSingleThreadExecutor()) {
            var future=pool.submit(() -> jobs.export(b,null)); assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            try { code(() -> jobs.export(b,null),"MUSE_EXPORT_BUSY"); } finally { release.countDown(); }
            var first=future.get(); assertThat(jobs.export(b,null).jobId()).isEqualTo(first.jobId());
        }
        verify(fetcher,times(1)).fetch(any());
    }
    @Test void incompleteExportFailureRetriesAndProjectionFailureKeepsLinks() throws Exception {
        String b=batch(1); when(fetcher.fetch(any())).thenThrow(new BusinessException("ORIGINAL_FETCH_FAILED","safe failure"));
        code(() -> jobs.export(b,null),"ORIGINAL_FETCH_FAILED");
        doReturn(new OriginalImageFetcher.ImageBytes(OriginalImageSecurityTest.png(),"image/png","png",2,3)).when(fetcher).fetch(any());
        var v=jobs.export(b,null);Path projection=Path.of(v.exportPath()).resolve("batches/BATCH-"+b+"/result-links.json");
        Files.delete(projection);Files.createDirectory(projection);
        var saved=jobs.submit(b,List.of(link(v,0)),null);assertThat(saved.linksFileStatus()).isEqualTo("SYNC_PENDING");
        Files.delete(projection);assertThat(jobs.results(b).linksFileStatus()).isEqualTo("SYNCED");
        assertThat(jobs.results(b).results()).isEqualTo(saved.results());
    }
    @Test void corruptPackageIsNeverSilentlyRegeneratedAndCancelledBatchCannotExport() throws Exception {
        var v=jobs.export(batch(1),null);
        Path original=Path.of(v.exportPath()).resolve("batches/BATCH-"+v.batchId()+"/original/"+v.manifest().originals().getFirst().fileName());
        Files.write(original,new byte[]{1,2,3}); code(() -> jobs.export(v.batchId(),null),"MUSE_PACKAGE_CORRUPT");
        String cancelled=batch(1);batches.control(cancelled,"CANCEL");code(() -> jobs.export(cancelled,null),"MUSE_BATCH_BLOCKED");
    }
    @Test void resultTransactionFailureLeavesNoPartialRegistration() throws Exception {
        var v=jobs.export(batch(2),null);
        jdbc.execute("CREATE TRIGGER fail_result BEFORE INSERT ON ms_muse_result WHEN NEW.asset_id='"+id(v,1)+"' BEGIN SELECT RAISE(ABORT,'fixture'); END");
        code(() -> jobs.submit(v.batchId(),List.of(link(v,0),link(v,1)),null),"MUSE_STORAGE_FAILED");
        assertThat(jobs.results(v.batchId()).results()).isEmpty();
        jdbc.execute("DROP TRIGGER fail_result");assertThat(jobs.submit(v.batchId(),List.of(link(v,0),link(v,1)),null).results()).hasSize(2);
    }
    @Test void publishedPackageWithInterruptedActiveReservationRecoversWithoutWaitingOrDownloading() throws Exception {
        var v=jobs.export(batch(1),null);
        jdbc.update("UPDATE ms_muse_job SET status='CREATING',package_json=NULL,build_token=?,lease_until='2099-01-01T00:00:00.000Z' WHERE job_id=?",
                UUID.randomUUID().toString(),v.jobId());
        var recovered=jobs.export(v.batchId(),null);
        assertThat(recovered.jobId()).isEqualTo(v.jobId());assertThat(recovered.manifest()).isEqualTo(v.manifest());
        verify(fetcher,times(1)).fetch(any());
    }
}
