package com.miaoshou.mcp.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.miaoshou.mcp.mcp.ProductTools;
import com.miaoshou.mcp.mcp.OptimizationTools;
import com.miaoshou.mcp.mcp.ProductImageTools;
import com.miaoshou.mcp.mcp.ImageBatchTools;
import java.time.Clock;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RuntimeConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer decimalJson() {
        return builder -> builder.featuresToEnable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    @Bean
    ToolCallbackProvider productToolCallbacks(ProductTools tools, OptimizationTools optimization, ProductImageTools images,
                                              ImageBatchTools batches) {
        return MethodToolCallbackProvider.builder().toolObjects(tools, optimization, images, batches).build();
    }
}
