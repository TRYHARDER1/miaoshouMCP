package com.miaoshou.mcp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.DraftRow;
import com.miaoshou.mcp.domain.SnapshotRow;
import com.miaoshou.mcp.repository.DraftMapper;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DraftService {
    private static final String WEIGHT_KIND = "WEIGHT_PLUS_0_01_V1";
    private final SnapshotService snapshots;
    private final DraftMapper drafts;
    private final WeightPolicy weights;
    private final ProductValidator validator;
    private final ObjectMapper mapper;
    private final Clock clock;

    public DraftService(SnapshotService snapshots, DraftMapper drafts, WeightPolicy weights,
                        ProductValidator validator, ObjectMapper mapper, Clock clock) {
        this.snapshots = snapshots;
        this.drafts = drafts;
        this.weights = weights;
        this.validator = validator;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> weightDraft(String snapshotId) {
        SnapshotRow snapshot = snapshots.find(snapshotId);
        JsonNode original = snapshots.product(snapshot);
        JsonNode product = weights.draft(original);
        validator.assertIdentityUnchanged(original, product);
        drafts.insertIfAbsent(new DraftRow(UUID.randomUUID().toString(), snapshotId, WEIGHT_KIND,
                "PENDING_REVIEW", product.toString(), clock.instant().toString()));
        return preview(drafts.findBySnapshot(snapshotId, WEIGHT_KIND).id());
    }

    public Map<String, Object> preview(String draftId) {
        DraftRow row = drafts.find(draftId);
        if (row == null) {
            throw new BusinessException("DRAFT_NOT_FOUND", "Unknown draft ID");
        }
        SnapshotRow snapshot = snapshots.find(row.snapshotId());
        JsonNode product;
        try {
            product = mapper.readTree(row.draftJson());
        } catch (JsonProcessingException exception) {
            throw new BusinessException("DRAFT_INVALID", "Stored draft JSON is invalid");
        }
        return Map.of("draftId", row.id(), "snapshotId", snapshot.id(), "detailId", snapshot.detailId(),
                "shopId", snapshot.shopId(), "baselineOssMd5", snapshot.ossMd5(), "status", row.status(),
                "changes", JsonDiff.compare(snapshots.product(snapshot), product), "draftProduct", product);
    }
}
