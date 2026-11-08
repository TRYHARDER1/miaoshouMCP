package com.miaoshou.mcp.service.muse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.image.ImageAsset;
import com.miaoshou.mcp.domain.image.muse.MuseModels.Context;
import com.miaoshou.mcp.domain.image.batch.BatchModels.BatchStatus;
import com.miaoshou.mcp.repository.ImageBatchMapper;
import com.miaoshou.mcp.service.ProductImageService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Component;

/** Read-only M5.2 access: deliberately avoids its lease-recovering get operation. */
@Component
public class MuseBatchReader {
    private final ImageBatchMapper batches;
    private final ProductImageService images;
    private final ObjectMapper json;
    public MuseBatchReader(ImageBatchMapper batches, ProductImageService images, ObjectMapper json) {
        this.batches=batches; this.images=images; this.json=json;
    }
    /** Validate context and optionally reject batches whose current control state blocks handoff. */
    public Context read(String batchId, boolean requireEligible) {
        var batch=batches.batch(batchId);
        if (batch==null) { throw error("BATCH_NOT_FOUND"); }
        if (requireEligible && (batch.status()==BatchStatus.PAUSED || batch.status()==BatchStatus.CANCELLED
                || batch.status()==BatchStatus.PROCESSING)) { throw error("MUSE_BATCH_BLOCKED"); }
        var plan=batches.plan(batch.planId());
        var manifest=images.extract(plan.snapshotId());
        try {
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(manifest).getBytes(StandardCharsets.UTF_8)));
            if (!manifest.validation().valid() || !digest.equals(plan.manifestHash())) { throw error("BATCH_MANIFEST_CHANGED"); }
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw error("MUSE_CONTEXT_INVALID"); }
        Map<String,ImageAsset> byId=new HashMap<>(); manifest.assets().forEach(a -> byId.put(a.assetId(),a));
        List<ImageAsset> assets=new ArrayList<>();
        for (var task:batches.tasks(batchId)) {
            var asset=byId.get(task.assetId());
            if (asset==null || asset.sequence()!=task.sequence() || assets.contains(asset)
                    || asset.status()!=ImageAsset.Status.PENDING) { throw error("MUSE_CONTEXT_INVALID"); }
            assets.add(asset);
        }
        if (assets.isEmpty() || assets.size()>4) { throw error("MUSE_CONTEXT_INVALID"); }
        return new Context(plan.snapshotId(),plan.id(),batchId,batch.sequence()+1,
                batches.batches(plan.id()).size(),"Singapore","English",assets);
    }
    private BusinessException error(String code) { return new BusinessException(code,"Muse context rejected: "+code); }
}
