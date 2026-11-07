package com.miaoshou.mcp.domain;

public record SnapshotRow(String id, long detailId, long shopId, String site,
                          String ossMd5, String originalJson, String createdAt) { }
