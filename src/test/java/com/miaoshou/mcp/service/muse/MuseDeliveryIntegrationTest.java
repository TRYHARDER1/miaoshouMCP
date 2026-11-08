package com.miaoshou.mcp.service.muse;

import com.miaoshou.mcp.Fixtures;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.SnapshotRow;
import com.miaoshou.mcp.repository.SnapshotMapper;
import com.miaoshou.mcp.service.ImageBatchService;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="spring.ai.mcp.server.enabled=false")
class MuseDeliveryIntegrationTest {
    static final Path ROOT=root();
    static Path root() { try { return Files.createTempDirectory("miaoshou-delivery-"); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",() -> "jdbc:sqlite:"+ROOT.resolve("test.db"));
        r.add("muse.jobs-root",() -> ROOT.resolve("internal").toString());
        r.add("muse.delivery-root",() -> ROOT.resolve("external").toString());
    }
    @Autowired MuseDeliveryService deliveries;
    @Autowired MuseJobService jobs;
    @Autowired ImageBatchService batches;
    @Autowired SnapshotMapper snapshots;
    @MockitoBean OriginalImageFetcher fetcher;
    @BeforeEach void init() throws Exception {
        when(fetcher.fetch(any())).thenReturn(new OriginalImageFetcher.ImageBytes(OriginalImageSecurityTest.png(),"image/png","png",2,3));
    }
    String batch(int count,boolean sku) throws Exception {
        var envelope=Fixtures.envelope();var p=(com.fasterxml.jackson.databind.node.ObjectNode)envelope.path("shopCollectItemInfo");
        if(!sku) {
            var urls=p.putArray("imgUrls");for(int i=0;i<count;i++) { urls.add("https://example.com/"+i+"?signature=PRIVATE_SIGNATURE&token=PRIVATE_TOKEN"); }
            p.path("skuPropertyList").forEach(d -> d.path("attrValueList").forEach(v -> ((com.fasterxml.jackson.databind.node.ObjectNode)v).putNull("imgUrl")));
        }
        String sid=UUID.randomUUID().toString();snapshots.insert(new SnapshotRow(sid,17,23,"SG","baseline",envelope.toString(),Instant.now().toString()));
        return batches.create(sid,null).batches().getFirst().batch().id();
    }
    @ParameterizedTest @ValueSource(ints={1,4})
    void externalWhitelistAndCopiesAreIdempotentWhileInternalPackageRemainsExact(int count) throws Exception {
        String b=batch(count,false);var internal=jobs.export(b,null);
        byte[] jobBefore=Files.readAllBytes(Path.of(internal.exportPath()).resolve("job.json"));
        var view=deliveries.export(b);assertThat(view.manifest().assets()).hasSize(count);
        var text=Fixtures.JSON.writeValueAsString(view.manifest())+view.prompt();
        assertThat(text).doesNotContain("PRIVATE_SIGNATURE","PRIVATE_TOKEN","https://","originalUrl","references","skuMapKeys",b,internal.jobId());
        assertThat(Fixtures.JSON.valueToTree(view.manifest()).propertyStream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("deliverySchemaVersion","policyVersion","deliveryId","market","language","assets");
        for(var a:view.manifest().assets()) {
            assertThat(Files.readAllBytes(Path.of(view.exportPath()).resolve(a.fileName()))).isEqualTo(OriginalImageSecurityTest.png());
            assertThat(Fixtures.JSON.valueToTree(a).propertyStream().map(Map.Entry::getKey).toList())
                    .containsExactlyInAnyOrder("assetId","fileName","roles","instructions","deliveryOrder");
        }
        assertThat(deliveries.export(b)).isEqualTo(view);
        assertThat(Files.readAllBytes(Path.of(internal.exportPath()).resolve("job.json"))).isEqualTo(jobBefore);
        assertThat(jobs.export(b,null).manifest()).isEqualTo(internal.manifest());
        verify(fetcher,times(count)).fetch(any());
    }
    @Test void skuAssociationsStayInternalAndCandidateIsNotPromoted() throws Exception {
        String b=batch(2,true);var original=jobs.export(b,null);var safe=deliveries.export(b);
        assertThat(original.manifest().context().assets().getFirst().references().getLast().skuMapKeys()).hasSize(2);
        assertThat(safe.prompt()).contains("MAIN_IMAGE_CANDIDATE","UNCLASSIFIED","SKU_IMAGE").doesNotContain(";a;b;",";a;d;");
        assertThat(safe.manifest().assets().getFirst().roles()).doesNotContain("MAIN_IMAGE");
    }
    @Test void unexpectedFileIsRejectedAndNeverRemoved() throws Exception {
        String b=batch(1,false);jobs.export(b,null);var v=deliveries.export(b);
        Path rogue=Path.of(v.exportPath()).resolve("result-links.json");Files.writeString(rogue,"private");
        assertThatThrownBy(() -> deliveries.export(b)).isInstanceOf(BusinessException.class);
        assertThat(rogue).hasContent("private");
    }
    @Test void concurrentExportHasOneStableDestination() throws Exception {
        String b=batch(1,false);jobs.export(b,null);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> deliveries.export(b));var c=pool.submit(() -> deliveries.export(b));
            assertThat(a.get()).isEqualTo(c.get());
        }
        verify(fetcher,times(1)).fetch(any());
    }
    @Test void deliveryDoesNotCreateOrDownloadMissingInternalJob() throws Exception {
        String b=batch(1,false);
        assertThatThrownBy(() -> deliveries.export(b)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(fetcher);
    }
}
