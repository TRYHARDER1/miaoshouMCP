package com.miaoshou.mcp.domain;

import com.fasterxml.jackson.databind.JsonNode;

public record JsonChange(String path, boolean beforeExists, JsonNode before,
                         boolean afterExists, JsonNode after) { }
