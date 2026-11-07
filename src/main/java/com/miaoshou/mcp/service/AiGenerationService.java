package com.miaoshou.mcp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.AiModels.Generated;
import com.miaoshou.mcp.domain.AiModels.GenerationRecord;
import com.miaoshou.mcp.domain.AiModels.GenerationView;
import com.miaoshou.mcp.domain.SnapshotRow;
import com.miaoshou.mcp.provider.llm.LlmProvider;
import com.miaoshou.mcp.provider.llm.LlmTask;
import com.miaoshou.mcp.repository.GenerationMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class AiGenerationService {
    private final LlmProvider provider;
    private final GenerationMapper records;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AiGenerationService(LlmProvider provider, GenerationMapper records, ObjectMapper mapper, Clock clock) {
        this.provider = provider;
        this.records = records;
        this.mapper = mapper;
        this.clock = clock;
    }

    public <T> Generated<T> generate(SnapshotRow snapshot, LlmTask task, JsonNode input,
                                     Class<T> type, Consumer<T> validate) {
        String id = UUID.randomUUID().toString();
        String started = clock.instant().toString();
        ObjectNode inputSummary = mapper.valueToTree(Map.of("detailId", snapshot.detailId(), "shopId", snapshot.shopId(),
                "site", snapshot.site(), "inputCharacters", input.toString().length(),
                "inputSha256", sha256(input.toString())));
        if (input.has("keywords")) {
            inputSummary.set("keywords", input.get("keywords").deepCopy());
        }
        if (input.has("analysisGenerationId")) {
            inputSummary.set("analysisGenerationId", input.get("analysisGenerationId"));
        }
        String summary = inputSummary.toString();
        records.start(new GenerationRecord(id, snapshot.id(), task.name(), provider.name(), provider.model(),
                task.version(), started, null, summary, null, "RUNNING", "PENDING_REVIEW", null));
        JsonNode output = null;
        String model = provider.model();
        try {
            var response = provider.generate(new LlmProvider.Request(task.prompt(), input));
            model = response.model();
            output = mapper.getNodeFactory().textNode(response.content());
            if (!"stop".equals(response.finishReason())) {
                throw new BusinessException("LLM_OUTPUT_INCOMPLETE", "Model output did not finish normally");
            }
            if (response.content() == null || response.content().isBlank()) {
                throw new BusinessException("LLM_OUTPUT_EMPTY", "Model returned empty content");
            }
            output = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.content());
            if (!output.isObject()) {
                throw new BusinessException("AI_OUTPUT_INVALID", "Expected a JSON object from the model");
            }
            T result = parse(output, type);
            validate.accept(result);
            complete(id, snapshot, task, model, started, summary, output, "STRUCTURE_VALIDATED", null);
            return new Generated<>(id, result);
        } catch (Exception exception) {
            String code = exception instanceof BusinessException business ? business.code() : "AI_OUTPUT_INVALID";
            complete(id, snapshot, task, model, started, summary, output, "FAILED", code);
            throw new BusinessException(code, "AI generation " + id + " failed validation or provider execution; inspect its record");
        }
    }

    public GenerationRecord record(String id) {
        GenerationRecord record = records.find(id);
        if (record == null) {
            throw new BusinessException("GENERATION_NOT_FOUND", "Unknown AI generation ID");
        }
        return record;
    }

    public GenerationView view(String id) {
        GenerationRecord record = record(id);
        try {
            return new GenerationView(record.id(), record.snapshotId(), record.task(), record.provider(), record.model(),
                    record.promptVersion(), record.startedAt(), record.completedAt(), mapper.readTree(record.inputSummaryJson()),
                    record.outputJson() == null ? mapper.getNodeFactory().nullNode() : mapper.readTree(record.outputJson()),
                    record.validationStatus(), record.reviewStatus(), record.errorCode());
        } catch (JsonProcessingException exception) {
            throw new BusinessException("GENERATION_INVALID", "Stored generation JSON is invalid");
        }
    }

    public <T> T parse(JsonNode node, Class<T> type) throws JsonProcessingException {
        return mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES).readValue(node.toString());
    }

    private void complete(String id, SnapshotRow snapshot, LlmTask task, String model, String started,
                          String summary, JsonNode output, String status, String error) {
        records.complete(new GenerationRecord(id, snapshot.id(), task.name(), provider.name(), model,
                task.version(), started, clock.instant().toString(), summary,
                output == null ? null : output.toString(), status, "PENDING_REVIEW", error));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }
}
