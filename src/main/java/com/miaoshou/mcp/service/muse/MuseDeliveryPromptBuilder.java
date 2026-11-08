package com.miaoshou.mcp.service.muse;

import com.miaoshou.mcp.domain.image.muse.MuseDeliveryModels.Manifest;
import org.springframework.stereotype.Component;

/** Independent external template; historical MusePromptBuilder v1 is intentionally unchanged. */
@Component
public class MuseDeliveryPromptBuilder {
    public String build(Manifest manifest) {
        StringBuilder out=new StringBuilder("# Muse image delivery — Singapore / English\n\nReturn a separate result for each assetId. Never merge pictures or invent product facts.\n\n");
        for(var asset:manifest.assets()) {
            out.append("## ").append(asset.assetId()).append("\nFile: ").append(asset.fileName())
                    .append("\nRoles: ").append(String.join(", ",asset.roles()))
                    .append("\n").append(asset.instructions()).append("\n\n");
        }
        return out.toString();
    }
}
