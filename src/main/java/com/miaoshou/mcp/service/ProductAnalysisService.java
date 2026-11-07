package com.miaoshou.mcp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.AiModels.Analysis;
import com.miaoshou.mcp.domain.AiModels.Generated;
import com.miaoshou.mcp.provider.llm.LlmTask;
import org.springframework.stereotype.Service;

@Service
public class ProductAnalysisService {
    private final SnapshotService snapshots;
    private final AiGenerationService generations;
    private final GeneratedTextValidator validator;
    private final ObjectMapper mapper;

    public ProductAnalysisService(SnapshotService snapshots, AiGenerationService generations,
                                   GeneratedTextValidator validator, ObjectMapper mapper) {
        this.snapshots = snapshots;
        this.generations = generations;
        this.validator = validator;
        this.mapper = mapper;
    }

    public Generated<Analysis> analyze(String snapshotId) {
        var snapshot = snapshots.find(snapshotId);
        var product = snapshots.product(snapshot);
        var input = mapper.createObjectNode();
        input.set("sourceProduct", ProductFacts.extract(product));
        return generations.generate(snapshot, LlmTask.ANALYSIS, input, Analysis.class,
                analysis -> validator.analysis(product, analysis));
    }

    public Analysis result(String snapshotId, String generationId) {
        var record = generations.record(generationId);
        if (!snapshotId.equals(record.snapshotId()) || !LlmTask.ANALYSIS.name().equals(record.task())
                || !"STRUCTURE_VALIDATED".equals(record.validationStatus())) {
            throw new BusinessException("ANALYSIS_MISMATCH", "Analysis must be validated and belong to this snapshot");
        }
        try {
            return generations.parse(mapper.readTree(record.outputJson()), Analysis.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("ANALYSIS_INVALID", "Stored analysis JSON is invalid");
        }
    }
}
