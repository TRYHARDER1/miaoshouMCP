package com.miaoshou.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.miaoshou.mcp.Fixtures;
import com.miaoshou.mcp.domain.ProductRef;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"spring.ai.mcp.server.enabled=false", "spring.datasource.url=jdbc:sqlite::memory:"})
class SnapshotDraftIntegrationTest {
    @MockitoBean ProductReadService products;
    @Autowired SnapshotService snapshots;
    @Autowired DraftService drafts;
    @Autowired JdbcTemplate jdbc;

    @Test
    void capturesUnknownFieldsAndReturnsSameDraftDuringConcurrentRetries() throws Exception {
        ProductRef ref = new ProductRef(17, 23);
        when(products.detail(ref)).thenReturn(Fixtures.envelope());
        var snapshot = snapshots.capture(ref);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> drafts.weightDraft(snapshot.id()));
            var two = executor.submit(() -> drafts.weightDraft(snapshot.id()));
            Map<String, Object> first = one.get();
            Map<String, Object> second = two.get();
            assertThat(first.get("draftId")).isEqualTo(second.get("draftId"));
            assertThat(first.get("status")).isEqualTo("PENDING_REVIEW");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ms_product_draft WHERE snapshot_id=?",
                    Integer.class, snapshot.id())).isEqualTo(1);
        }
        assertThat(snapshots.find(snapshot.id()).originalJson()).isEqualTo(snapshot.originalJson());
        assertThat(snapshots.product(snapshot).path("platformField").path("keep").asBoolean()).isTrue();
        assertThat(snapshots.product(snapshot).path("weight").decimalValue()).isEqualByComparingTo("0.50");
    }
}
