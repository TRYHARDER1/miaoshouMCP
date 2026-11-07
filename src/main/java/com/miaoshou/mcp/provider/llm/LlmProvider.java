package com.miaoshou.mcp.provider.llm;

import com.fasterxml.jackson.databind.JsonNode;

public interface LlmProvider {
    String name();
    String model();
    Response generate(Request request);

    record Request(String systemPrompt, JsonNode input) { }
    record Response(String model, String content, String finishReason) { }
}
