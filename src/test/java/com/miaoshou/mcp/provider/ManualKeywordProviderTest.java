package com.miaoshou.mcp.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.provider.keyword.ManualKeywordProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManualKeywordProviderTest {
    @Test
    void labelsSourcesDeduplicatesAndKeepsMetricsUnknown() {
        var result = new ManualKeywordProvider().collect("SG", List.of(" Cat Toy "), List.of("cat toy", "pet toy"));
        assertThat(result.keywords()).hasSize(2);
        assertThat(result.keywords().getFirst().source()).isEqualTo("manual");
        assertThat(result.keywords().getLast().source()).isEqualTo("model_recommendation");
        assertThat(result.keywords()).allSatisfy(keyword -> {
            assertThat(keyword.searchVolume()).isNull();
            assertThat(keyword.growthRate()).isNull();
        });
    }

    @Test
    void rejectsInvalidKeywords() {
        assertThatThrownBy(() -> new ManualKeywordProvider().collect("SG", List.of("<script>"), List.of()))
                .isInstanceOf(BusinessException.class);
    }
}
