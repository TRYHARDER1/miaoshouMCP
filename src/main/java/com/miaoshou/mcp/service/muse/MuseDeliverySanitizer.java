package com.miaoshou.mcp.service.muse;

import com.miaoshou.mcp.domain.image.muse.MuseDeliveryModels.*;
import com.miaoshou.mcp.domain.image.muse.MuseModels.PackageManifest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

/** Constructs a whitelist instead of redacting a serialized internal object. */
@Component
public class MuseDeliverySanitizer {
    public static final String POLICY="muse-safe-v1";
    /** Preserve identity and processing roles without disclosing internal association metadata. */
    public Manifest sanitize(PackageManifest source) {
        List<Asset> assets=new ArrayList<>();
        for(int i=0;i<source.context().assets().size();i++) {
            var original=source.context().assets().get(i); var roles=new LinkedHashSet<String>();
            if(original.references().stream().anyMatch(r -> r.candidateMain())) { roles.add("MAIN_IMAGE_CANDIDATE"); }
            original.assetTypes().forEach(t -> roles.add(t.name()));
            String instructions="Translate Chinese into accurate natural English. Preserve true color, shape, material, quantity, accessories, structure and necessary brand marks. Invent no features or specifications. Produce one independent output labeled with this assetId; never substitute or combine images. Report failures explicitly.";
            if(roles.contains("MAIN_IMAGE_CANDIDATE")) { instructions+=" This is a candidate only, not a confirmed official main image."; }
            if(roles.contains("SKU_IMAGE")) { instructions+=" Preserve this exact variant without recoloring or substitution."; }
            if(roles.contains("DETAIL_IMAGE")) { instructions+=" Preserve factual diagrams and dimensions."; }
            if(roles.contains("UNCLASSIFIED")) { instructions+=" Keep the unclassified role."; }
            assets.add(new Asset(original.assetId(),"images/"+source.originals().get(i).fileName(),List.copyOf(roles),instructions,i+1));
        }
        String id=UUID.nameUUIDFromBytes((POLICY+":"+source.jobId()).getBytes(StandardCharsets.UTF_8)).toString();
        return new Manifest(1,POLICY,id,"Singapore","English",assets);
    }
}
