package com.miaoshou.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miaoshou.mcp.Fixtures;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.AiModels.GenerationRecord;
import com.miaoshou.mcp.domain.SnapshotRow;
import com.miaoshou.mcp.provider.llm.LlmProvider;
import com.miaoshou.mcp.provider.llm.LlmTask;
import com.miaoshou.mcp.repository.GenerationMapper;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class AiGenerationServiceTest {
    private final LlmProvider provider = mock(LlmProvider.class);
    private final GenerationMapper records = mock(GenerationMapper.class);
    private final AiGenerationService service = new AiGenerationService(provider, records, Fixtures.JSON, Clock.systemUTC());
    private final SnapshotRow snapshot = new SnapshotRow("snapshot", 17, 23, "SG", "md5", "{}", "now");

    record Output(String value) { }

    @BeforeEach
    void setup() {
        when(provider.name()).thenReturn("deepseek");
        when(provider.model()).thenReturn("configured-model");
    }

    @Test
    void recordsActualModelAndPendingHumanReview() {
        when(provider.generate(any())).thenReturn(new LlmProvider.Response("actual-model", "{\"value\":\"ok\"}", "stop"));
        var result = service.generate(snapshot, LlmTask.ANALYSIS, Fixtures.JSON.createObjectNode(), Output.class, value -> { });
        assertThat(result.result().value()).isEqualTo("ok");
        ArgumentCaptor<GenerationRecord> completed = ArgumentCaptor.forClass(GenerationRecord.class);
        verify(records).complete(completed.capture());
        assertThat(completed.getValue().model()).isEqualTo("actual-model");
        assertThat(completed.getValue().validationStatus()).isEqualTo("STRUCTURE_VALIDATED");
        assertThat(completed.getValue().reviewStatus()).isEqualTo("PENDING_REVIEW");
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "not JSON", "{}", "{\"value\":null}", "{\"value\":\"ok\",\"extra\":true}", "{\"value\":\"ok\"} {}"})
    void rejectsIncompleteOrUnexpectedStructureAndPreservesFailureRecord(String output) {
        when(provider.generate(any())).thenReturn(new LlmProvider.Response("model", output, "stop"));
        assertThatThrownBy(() -> service.generate(snapshot, LlmTask.ANALYSIS, Fixtures.JSON.createObjectNode(), Output.class, value -> { }))
                .isInstanceOf(BusinessException.class);
        ArgumentCaptor<GenerationRecord> completed = ArgumentCaptor.forClass(GenerationRecord.class);
        verify(records).complete(completed.capture());
        assertThat(completed.getValue().validationStatus()).isEqualTo("FAILED");
        assertThat(completed.getValue().outputJson()).isNotNull();
    }

    @Test
    void rejectsTokenTruncationEvenWhenPartialJsonParses() {
        when(provider.generate(any())).thenReturn(new LlmProvider.Response("model", "{\"value\":\"ok\"}", "length"));
        assertThatThrownBy(() -> service.generate(snapshot, LlmTask.CONTENT, Fixtures.JSON.createObjectNode(), Output.class, value -> { }))
                .isInstanceOf(BusinessException.class);
        ArgumentCaptor<GenerationRecord> completed = ArgumentCaptor.forClass(GenerationRecord.class);
        verify(records).complete(completed.capture());
        assertThat(completed.getValue().errorCode()).isEqualTo("LLM_OUTPUT_INCOMPLETE");
    }
}
