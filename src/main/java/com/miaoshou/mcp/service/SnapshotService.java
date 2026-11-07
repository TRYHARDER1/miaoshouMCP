package com.miaoshou.mcp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.ProductRef;
import com.miaoshou.mcp.domain.SnapshotRow;
import com.miaoshou.mcp.repository.SnapshotMapper;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SnapshotService {
    private final ProductReadService products;
    private final SnapshotMapper snapshots;
    private final ObjectMapper mapper;
    private final Clock clock;

    public SnapshotService(ProductReadService products, SnapshotMapper snapshots, ObjectMapper mapper, Clock clock) {
        this.products = products;
        this.snapshots = snapshots;
        this.mapper = mapper;
        this.clock = clock;
    }

    public SnapshotRow capture(ProductRef ref) {
        JsonNode envelope = products.detail(ref);
        String site = envelope.path("shopCollectItemInfo").path("site").asText();
        SnapshotRow row = new SnapshotRow(UUID.randomUUID().toString(), ref.detailId(), ref.shopId(), site,
                envelope.path("ossMd5").asText(), envelope.toString(), clock.instant().toString());
        snapshots.insert(row);
        return row;
    }

    public SnapshotRow find(String id) {
        SnapshotRow row = snapshots.find(id);
        if (row == null) {
            throw new BusinessException("SNAPSHOT_NOT_FOUND", "Unknown snapshot ID");
        }
        return row;
    }

    public JsonNode product(SnapshotRow row) {
        try {
            return mapper.readTree(row.originalJson()).path("shopCollectItemInfo");
        } catch (JsonProcessingException exception) {
            throw new BusinessException("SNAPSHOT_INVALID", "Stored snapshot JSON is invalid");
        }
    }
}
