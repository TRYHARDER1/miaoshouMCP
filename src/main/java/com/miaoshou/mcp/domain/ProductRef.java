package com.miaoshou.mcp.domain;

public record ProductRef(long detailId, long shopId) {
    public ProductRef {
        if (detailId <= 0 || shopId <= 0) {
            throw new IllegalArgumentException("detailId and shopId must be positive integers");
        }
    }
}
