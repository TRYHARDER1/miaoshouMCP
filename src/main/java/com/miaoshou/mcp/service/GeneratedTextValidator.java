package com.miaoshou.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.AiModels.Analysis;
import com.miaoshou.mcp.domain.AiModels.Content;
import com.miaoshou.mcp.domain.AiModels.Evidence;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

@Component
public class GeneratedTextValidator {
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern CJK = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]");

    public void analysis(JsonNode original, Analysis analysis) {
        List<String> claims = new ArrayList<>(List.of(analysis.productName(), analysis.mainFunction()));
        claims.addAll(analysis.targetAudience());
        claims.addAll(analysis.painPoints());
        claims.addAll(analysis.sellingPoints());
        claims.addAll(analysis.usageScenarios());
        if (analysis.sellingPoints().isEmpty() || analysis.recommendedKeywords().isEmpty()
                || analysis.recommendedKeywords().size() > 10) {
            fail("Analysis requires selling points and 1-10 recommended keywords");
        }
        for (String keyword : analysis.recommendedKeywords()) {
            english(keyword, 80);
        }
        supported(original, claims, analysis.evidence());
    }

    public void content(JsonNode original, Content content) {
        english(content.title(), 255);
        if (content.title().codePointCount(0, content.title().length()) < 25 || content.benefits().isEmpty()) {
            fail("Title must have at least 25 characters and benefits must be present");
        }
        List<String> claims = new ArrayList<>(List.of(content.title(), content.hook()));
        claims.addAll(content.benefits());
        claims.addAll(content.usageScenarios());
        claims.addAll(content.specifications());
        claims.addAll(content.precautions());
        supported(original, claims, content.evidence());
    }

    public void english(String value, int maximum) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maximum
                || CJK.matcher(value).find()) {
            fail("Generated text must be nonempty English within the field length limit");
        }
        String decoded = value;
        for (int round = 0; round < 3; round++) {
            decoded = Parser.unescapeEntities(decoded, false);
            if (decoded.contains("<") || decoded.contains(">")) {
                fail("Generated plain text contains markup or unsafe encoded angle brackets");
            }
        }
    }

    public void translatedLabel(String original, String translated) {
        english(translated, 255);
        if (!numberSequence(original).equals(numberSequence(translated))) {
            fail("SKU translation changed numeric specifications");
        }
    }

    private void supported(JsonNode original, List<String> claims, List<Evidence> evidence) {
        if (claims.size() > 50 || evidence == null || evidence.size() > 100) {
            fail("Generated claim or evidence count exceeds the limit");
        }
        JsonNode facts = ProductFacts.extract(original);
        Set<String> cited = new HashSet<>();
        for (Evidence source : evidence) {
            if (source.claim() == null || source.sourcePath() == null || source.sourceText() == null
                    || source.sourceText().isBlank() || source.sourceText().length() > 2000) {
                fail("Evidence requires a claim, source JSON Pointer and verbatim quote");
            }
            JsonNode fact;
            try {
                fact = facts.at(source.sourcePath());
            } catch (IllegalArgumentException exception) {
                throw new BusinessException("AI_OUTPUT_INVALID", "Evidence contains an invalid JSON Pointer");
            }
            if (fact.isMissingNode() || !fact.isValueNode() || !fact.asText().contains(source.sourceText())) {
                throw new BusinessException("AI_EVIDENCE_MISMATCH", "Evidence quote is absent from the original source field");
            }
            cited.add(source.claim());
        }
        Set<String> numbers = sourceNumbers(facts);
        for (String claim : claims) {
            english(claim, 500);
            if (!cited.contains(claim)) {
                throw new BusinessException("AI_EVIDENCE_MISSING", "Every generated claim requires source evidence");
            }
            if (!numbers.containsAll(tokens(claim))) {
                throw new BusinessException("AI_NUMERIC_FACT_UNSUPPORTED", "Generated numeric values must appear in the source facts");
            }
        }
    }

    private Set<String> sourceNumbers(JsonNode facts) {
        Set<String> result = new HashSet<>();
        var fields = facts.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (Set.of("attrId", "attrValueId", "attributeId", "valueId").contains(field.getKey())) {
                continue;
            }
            JsonNode value = field.getValue();
            if (value.isValueNode()) {
                result.addAll(tokens(value.asText()));
            } else if (value.isArray()) {
                for (JsonNode element : value) {
                    if (element.isObject()) {
                        result.addAll(sourceNumbers(element));
                    }
                }
            }
        }
        return result;
    }

    private Set<String> tokens(String value) {
        return new HashSet<>(numberSequence(value));
    }

    private List<String> numberSequence(String value) {
        List<String> result = new ArrayList<>();
        var matcher = NUMBER.matcher(value);
        while (matcher.find()) {
            result.add(new BigDecimal(matcher.group()).stripTrailingZeros().toPlainString());
        }
        return result;
    }

    private static void fail(String message) {
        throw new BusinessException("AI_OUTPUT_INVALID", message);
    }
}
