package com.miaoshou.mcp.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public final class AiModels {
    private AiModels() { }

    public record Evidence(String claim, String sourcePath, String sourceText) { }

    public record Analysis(String productName, String mainFunction, List<String> targetAudience,
                           List<String> painPoints, List<String> sellingPoints, List<String> usageScenarios,
                           List<String> recommendedKeywords, List<Evidence> evidence) { }

    public record Content(String title, String hook, List<String> benefits, List<String> usageScenarios,
                          List<String> specifications, List<String> precautions, List<Evidence> evidence) { }

    public record ValueTranslation(String attrValueId, String attrValue) { }
    public record PropertyTranslation(int propertyIndex, String attrName, List<ValueTranslation> values) { }
    public record SkuTranslation(List<PropertyTranslation> properties) { }

    public record Keyword(String keyword, Long searchVolume, Double growthRate, String source) { }
    public record Keywords(String site, List<Keyword> keywords) { }

    public record Generated<T>(String generationId, T result) { }
    public record GenerationRecord(String id, String snapshotId, String task, String provider, String model,
                                   String promptVersion, String startedAt, String completedAt,
                                   String inputSummaryJson, String outputJson, String validationStatus,
                                   String reviewStatus, String errorCode) { }

    public record GenerationView(String generationId, String snapshotId, String task, String provider, String model,
                                 String promptVersion, String startedAt, String completedAt, JsonNode inputSummary,
                                 JsonNode output, String validationStatus, String reviewStatus, String errorCode) { }
}
