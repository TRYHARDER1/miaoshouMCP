package com.miaoshou.mcp.provider.keyword;

import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.AiModels.Keyword;
import com.miaoshou.mcp.domain.AiModels.Keywords;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class ManualKeywordProvider implements KeywordProvider {
    @Override
    public Keywords collect(String site, List<String> manualKeywords, List<String> recommendations) {
        if (manualKeywords == null || recommendations == null || manualKeywords.size() > 30 || recommendations.size() > 30) {
            throw new BusinessException("KEYWORDS_INVALID", "Keyword lists are required and limited to 30 entries each");
        }
        LinkedHashMap<String, Keyword> keywords = new LinkedHashMap<>();
        add(keywords, manualKeywords, "manual");
        add(keywords, recommendations, "model_recommendation");
        return new Keywords(site, List.copyOf(keywords.values()));
    }

    private void add(LinkedHashMap<String, Keyword> result, List<String> words, String source) {
        for (String word : words) {
            if (word == null || word.isBlank() || word.length() > 80 || word.contains("<") || word.contains(">")) {
                throw new BusinessException("KEYWORDS_INVALID", "Keywords must be nonempty plain text of at most 80 characters");
            }
            String text = word.strip();
            result.putIfAbsent(text.toLowerCase(Locale.ROOT), new Keyword(text, null, null, source));
        }
    }
}
