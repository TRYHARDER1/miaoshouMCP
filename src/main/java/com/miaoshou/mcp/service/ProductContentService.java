package com.miaoshou.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miaoshou.mcp.domain.AiModels.Content;
import com.miaoshou.mcp.domain.AiModels.Generated;
import com.miaoshou.mcp.domain.AiModels.Keywords;
import com.miaoshou.mcp.provider.llm.LlmTask;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ProductContentService {
    private final SnapshotService snapshots;
    private final ProductAnalysisService analyses;
    private final AiGenerationService generations;
    private final GeneratedTextValidator validator;
    private final HtmlDescriptionBuilder html;
    private final DraftService drafts;
    private final ObjectMapper mapper;

    public ProductContentService(SnapshotService snapshots, ProductAnalysisService analyses,
                                 AiGenerationService generations, GeneratedTextValidator validator,
                                 HtmlDescriptionBuilder html, DraftService drafts, ObjectMapper mapper) {
        this.snapshots = snapshots;
        this.analyses = analyses;
        this.generations = generations;
        this.validator = validator;
        this.html = html;
        this.drafts = drafts;
        this.mapper = mapper;
    }

    public Generated<Content> generate(String snapshotId, String analysisId, Keywords keywords) {
        var snapshot = snapshots.find(snapshotId);
        var original = snapshots.product(snapshot);
        var input = mapper.createObjectNode();
        input.set("sourceProduct", ProductFacts.extract(original));
        input.set("analysis", mapper.valueToTree(analyses.result(snapshotId, analysisId)));
        input.put("analysisGenerationId", analysisId);
        input.set("keywords", mapper.valueToTree(keywords));
        return generations.generate(snapshot, LlmTask.CONTENT, input, Content.class, content -> {
            validator.content(original, content);
            html.build(original.path("notes").asText(), content);
        });
    }

    public ObjectNode apply(String snapshotId, Content content) {
        var original = snapshots.product(snapshots.find(snapshotId));
        validator.content(original, content);
        ObjectNode draft = original.deepCopy();
        draft.put("title", content.title());
        draft.put("notes", html.build(original.path("notes").asText(), content));
        return draft;
    }

    public Map<String, Object> createDraft(String snapshotId, String analysisId, Keywords keywords) {
        var generated = generate(snapshotId, analysisId, keywords);
        var draft = drafts.create(snapshotId, "M4_CONTENT:" + generated.generationId(), apply(snapshotId, generated.result()));
        return Map.of("generationId", generated.generationId(), "keywords", keywords, "draft", draft);
    }
}
