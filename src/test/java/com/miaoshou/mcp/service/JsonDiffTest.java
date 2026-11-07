package com.miaoshou.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miaoshou.mcp.Fixtures;
import org.junit.jupiter.api.Test;

class JsonDiffTest {
    @Test
    void distinguishesMissingFromNullAndEscapesJsonPointerKeys() throws Exception {
        var original = Fixtures.JSON.readTree("{\"a/b~c\":null}");
        var draft = Fixtures.JSON.readTree("{\"weight\":0.51}");
        var changes = JsonDiff.compare(original, draft);
        assertThat(changes).hasSize(2);
        assertThat(changes.getFirst().path()).isEqualTo("/a~1b~0c");
        assertThat(changes.getFirst().beforeExists()).isTrue();
        assertThat(changes.getFirst().afterExists()).isFalse();
        assertThat(changes.getLast().beforeExists()).isFalse();
        assertThat(changes.getLast().afterExists()).isTrue();
    }
}
