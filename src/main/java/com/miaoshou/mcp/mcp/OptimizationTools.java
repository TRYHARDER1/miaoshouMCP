package com.miaoshou.mcp.mcp;

import com.miaoshou.mcp.domain.AiModels.Analysis;
import com.miaoshou.mcp.domain.AiModels.Generated;
import com.miaoshou.mcp.domain.AiModels.GenerationView;
import com.miaoshou.mcp.domain.AiModels.Keywords;
import com.miaoshou.mcp.service.AiGenerationService;
import com.miaoshou.mcp.service.ProductAnalysisService;
import com.miaoshou.mcp.service.ProductContentService;
import com.miaoshou.mcp.service.ProductOptimizationService;
import com.miaoshou.mcp.service.ProductTranslationService;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class OptimizationTools {
    private final ProductAnalysisService analyses;
    private final ProductOptimizationService optimization;
    private final ProductContentService content;
    private final ProductTranslationService translations;
    private final AiGenerationService generations;

    public OptimizationTools(ProductAnalysisService analyses, ProductOptimizationService optimization,
                              ProductContentService content, ProductTranslationService translations,
                              AiGenerationService generations) {
        this.analyses = analyses;
        this.optimization = optimization;
        this.content = content;
        this.translations = translations;
        this.generations = generations;
    }

    @Tool(name = "product_analyze", description = "Analyze an immutable snapshot with source citations. Calls configured LLM and records structured validation; human factual review remains pending.")
    public Generated<Analysis> analyze(String snapshotId) {
        return analyses.analyze(snapshotId);
    }

    @Tool(name = "product_research_keywords", description = "Merge manual keywords and a validated analysis's model recommendations. Sources are labeled; market metrics remain null.")
    public Keywords keywords(String snapshotId, String analysisId, List<String> manualKeywords) {
        return optimization.keywords(snapshotId, analysisId, manualKeywords);
    }

    @Tool(name = "product_optimize_content", description = "Generate English title and template HTML from a snapshot and validated analysis; persist a local pending-review draft.")
    public Map<String, Object> content(String snapshotId, String analysisId, List<String> manualKeywords) {
        return content.createDraft(snapshotId, analysisId, optimization.keywords(snapshotId, analysisId, manualKeywords));
    }

    @Tool(name = "product_translate_sku", description = "Translate all SKU display labels into English in a local draft, preserving IDs, keys, images, prices and inventory.")
    public Map<String, Object> sku(String snapshotId) {
        return translations.createDraft(snapshotId);
    }

    @Tool(name = "product_optimize", description = "Run analysis, labeled keywords, English content and complete SKU translation sequentially from one snapshot, recording each LLM call and one local pending-review draft.")
    public Map<String, Object> optimize(String snapshotId, List<String> manualKeywords) {
        return optimization.optimize(snapshotId, manualKeywords);
    }

    @Tool(name = "product_get_generation_record", description = "Read a persisted AI generation's provider, model, prompt version, input digest, output and validation/review status.")
    public GenerationView generation(String generationId) {
        return generations.view(generationId);
    }
}
