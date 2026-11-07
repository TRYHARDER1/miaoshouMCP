package com.miaoshou.mcp.service;

import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.AiModels.Content;
import java.net.URI;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

@Component
public class HtmlDescriptionBuilder {
    public String build(String originalHtml, Content content) {
        Document document = Document.createShell("");
        document.outputSettings().prettyPrint(false);
        Element root = document.body().appendElement("div");
        root.appendElement("p").text(content.hook());
        section(root, "Key benefits", content.benefits());
        section(root, "Usage scenarios", content.usageScenarios());
        section(root, "Specifications", content.specifications());
        section(root, "Care and use", content.precautions());
        for (Element image : Jsoup.parseBodyFragment(originalHtml).select("img")) {
            String source = image.attr("src");
            URI uri;
            try {
                uri = URI.create(source);
            } catch (IllegalArgumentException exception) {
                throw new BusinessException("IMAGE_SOURCE_INVALID", "Original description contains an invalid image URL");
            }
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null) {
                throw new BusinessException("IMAGE_SOURCE_INVALID", "Original description image must use an absolute HTTP URL");
            }
            root.appendElement("img").attr("src", source);
        }
        String html = document.body().html();
        if (html.length() > 10000) {
            throw new BusinessException("DESCRIPTION_TOO_LONG", "Generated HTML exceeds 10000 characters");
        }
        return html;
    }

    private void section(Element root, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        root.appendElement("h2").text(title);
        Element list = root.appendElement("ul");
        lines.forEach(line -> list.appendElement("li").text(line));
    }
}
