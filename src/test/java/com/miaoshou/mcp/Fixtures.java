package com.miaoshou.mcp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;

public final class Fixtures {
    public static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private Fixtures() { }

    public static ObjectNode envelope() throws IOException {
        try (var input = Fixtures.class.getResourceAsStream("/product.json")) {
            return (ObjectNode) JSON.readTree(input);
        }
    }

    public static ObjectNode product() throws IOException {
        return (ObjectNode) envelope().get("shopCollectItemInfo");
    }
}
