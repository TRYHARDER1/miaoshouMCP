package com.miaoshou.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miaoshou.mcp.domain.AiModels.Generated;
import com.miaoshou.mcp.domain.AiModels.SkuTranslation;
import com.miaoshou.mcp.provider.llm.LlmTask;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ProductTranslationService {
    private final SnapshotService snapshots;
    private final AiGenerationService generations;
    private final SkuTranslationPolicy policy;
    private final DraftService drafts;
    private final ObjectMapper mapper;

    public ProductTranslationService(SnapshotService snapshots, AiGenerationService generations,
                                     SkuTranslationPolicy policy, DraftService drafts, ObjectMapper mapper) {
        this.snapshots = snapshots;
        this.generations = generations;
        this.policy = policy;
        this.drafts = drafts;
        this.mapper = mapper;
    }

    public Generated<SkuTranslation> generate(String snapshotId) {
        var snapshot = snapshots.find(snapshotId);
        var original = snapshots.product(snapshot);
        var input = mapper.createObjectNode();
        var properties = input.putArray("properties");
        for (int index = 0; index < original.path("skuPropertyList").size(); index++) {
            var source = original.path("skuPropertyList").get(index);
            var property = properties.addObject();
            property.put("propertyIndex", index);
            property.put("attrName", source.path("attrName").asText());
            var values = property.putArray("values");
            for (var value : source.path("attrValueList")) {
                values.addObject().put("attrValueId", value.path("attrValueId").asText())
                        .put("attrValue", value.path("attrValue").asText());
            }
        }
        return generations.generate(snapshot, LlmTask.SKU_TRANSLATION, input, SkuTranslation.class,
                result -> policy.apply(original, result));
    }

    public ObjectNode apply(String snapshotId, SkuTranslation result) {
        return policy.apply(snapshots.product(snapshots.find(snapshotId)), result);
    }

    public Map<String, Object> createDraft(String snapshotId) {
        var generated = generate(snapshotId);
        return Map.of("generationId", generated.generationId(), "draft",
                drafts.create(snapshotId, "M4_SKU:" + generated.generationId(), apply(snapshotId, generated.result())));
    }
}
