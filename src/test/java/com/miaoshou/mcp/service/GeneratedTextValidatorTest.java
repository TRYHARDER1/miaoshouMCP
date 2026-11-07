package com.miaoshou.mcp.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaoshou.mcp.Fixtures;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.AiModels.Analysis;
import com.miaoshou.mcp.domain.AiModels.Evidence;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GeneratedTextValidatorTest {
    private final GeneratedTextValidator validator = new GeneratedTextValidator();

    @Test
    void acceptsSourceGroundedClaimsAndRejectsFalseCitations() throws Exception {
        JsonNode source = Fixtures.product();
        Analysis valid = analysis("Toy", "toy");
        assertThatCode(() -> validator.analysis(source, valid)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.analysis(source, analysis("Toy", "imaginary source")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void blocksUnsupportedNumbersEvenWithARealSourceQuote() throws Exception {
        JsonNode source = Fixtures.product();
        assertThatThrownBy(() -> validator.analysis(source, analysis("Toy lasts 9999 days", "toy")))
                .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"<script>bad</script>", "&lt;script&gt;", "&amp;lt;test&amp;gt;", "中文描述"})
    void rejectsMarkupEncodingAndUntranslatedChinese(String text) {
        assertThatThrownBy(() -> validator.english(text, 255)).isInstanceOf(BusinessException.class);
    }

    @Test
    void translatesLabelsWithoutChangingNumericSpecifications() {
        assertThatCode(() -> validator.translatedLabel("七彩球4.8cm", "Rainbow ball 4.8 cm")).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.translatedLabel("七彩球4.8cm", "Rainbow ball 48 cm"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.translatedLabel("2 x 2 pack", "2 pack"))
                .isInstanceOf(BusinessException.class);
    }

    private Analysis analysis(String name, String quote) {
        return new Analysis(name, "Play", List.of(), List.of(), List.of("Simple play"), List.of(), List.of("toy"),
                List.of(new Evidence(name, "/title", quote), new Evidence("Play", "/title", "toy"),
                        new Evidence("Simple play", "/title", "toy")));
    }
}
