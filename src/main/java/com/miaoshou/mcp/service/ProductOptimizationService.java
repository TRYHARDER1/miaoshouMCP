package com.miaoshou.mcp.service;

import com.miaoshou.mcp.domain.AiModels.Keywords;
import com.miaoshou.mcp.provider.keyword.KeywordProvider;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ProductOptimizationService {
    private final SnapshotService snapshots;
    private final ProductAnalysisService analyses;
    private final ProductContentService contents;
    private final ProductTranslationService translations;
    private final KeywordProvider keywords;
    private final DraftService drafts;

    public ProductOptimizationService(SnapshotService snapshots, ProductAnalysisService analyses,
                                      ProductContentService contents, ProductTranslationService translations,
                                      KeywordProvider keywords, DraftService drafts) {
        this.snapshots = snapshots;
        this.analyses = analyses;
        this.contents = contents;
        this.translations = translations;
        this.keywords = keywords;
        this.drafts = drafts;
    }

    public Keywords keywords(String snapshotId, String analysisId, List<String> manual) {
        return keywords.collect(snapshots.find(snapshotId).site(), manual,
                analyses.result(snapshotId, analysisId).recommendedKeywords());
    }

    public Map<String, Object> optimize(String snapshotId, List<String> manual) {
        keywords.collect(snapshots.find(snapshotId).site(), manual, List.of());
        var analysis = analyses.analyze(snapshotId);
        var words = keywords(snapshotId, analysis.generationId(), manual);
        var content = contents.generate(snapshotId, analysis.generationId(), words);
        var translation = translations.generate(snapshotId);
        var product = translations.apply(snapshotId, translation.result());
        var contentProduct = contents.apply(snapshotId, content.result());
        product.set("title", contentProduct.get("title"));
        product.set("notes", contentProduct.get("notes"));
        var draft = drafts.create(snapshotId, "M4_COMPLETE:" + translation.generationId(), product);
        return Map.of("analysisId", analysis.generationId(), "contentGenerationId", content.generationId(),
                "skuGenerationId", translation.generationId(), "keywords", words, "draft", draft);
    }
}
