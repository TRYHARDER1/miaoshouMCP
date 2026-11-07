package com.miaoshou.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.AiModels.Content;
import java.util.List;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class HtmlDescriptionBuilderTest {
    private final HtmlDescriptionBuilder builder = new HtmlDescriptionBuilder();

    @Test
    void buildsTextNodesAndPreservesImageOrderAndRepeatedUrls() {
        String html = builder.build("<img src='https://example.com/a?a=1&amp;b=2'><script>bad()</script>"
                + "<img src='https://example.com/a?a=1&amp;b=2'>", content("Play & enjoy"));
        var parsed = Jsoup.parseBodyFragment(html);
        assertThat(parsed.select("script")).isEmpty();
        assertThat(parsed.select("p").text()).isEqualTo("Play & enjoy");
        assertThat(parsed.select("img").eachAttr("src"))
                .containsExactly("https://example.com/a?a=1&b=2", "https://example.com/a?a=1&b=2");
    }

    @Test
    void rejectsUnsafeImageUrlAndOversizedDescription() {
        assertThatThrownBy(() -> builder.build("<img src='javascript:bad()'>", content("Play")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> builder.build("", content("a".repeat(10001))))
                .isInstanceOf(BusinessException.class);
    }

    private Content content(String hook) {
        return new Content("Example English product title", hook, List.of("Color options"),
                List.of(), List.of(), List.of(), List.of());
    }
}
