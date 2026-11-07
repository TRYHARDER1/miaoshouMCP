package com.miaoshou.mcp.domain;

public record DraftRow(String id, String snapshotId, String kind,
                       String status, String draftJson, String createdAt) { }
