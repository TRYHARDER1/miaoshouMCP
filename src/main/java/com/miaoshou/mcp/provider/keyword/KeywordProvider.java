package com.miaoshou.mcp.provider.keyword;

import com.miaoshou.mcp.domain.AiModels.Keywords;
import java.util.List;

public interface KeywordProvider {
    Keywords collect(String site, List<String> manualKeywords, List<String> recommendations);
}
