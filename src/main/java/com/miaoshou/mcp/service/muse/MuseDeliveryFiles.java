package com.miaoshou.mcp.service.muse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.image.muse.MuseDeliveryModels.Manifest;
import com.miaoshou.mcp.domain.image.muse.MuseModels.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Separate external tree containing exactly safe metadata, instructions and selected original copies. */
@Component
public class MuseDeliveryFiles {
    private final Path root;
    private final ObjectMapper json;
    private final MuseDeliveryPromptBuilder prompts;
    public MuseDeliveryFiles(@Value("${muse.delivery-root:.data/muse-deliveries}") String root,ObjectMapper json,MuseDeliveryPromptBuilder prompts) {
        this.root=Path.of(root).toAbsolutePath().normalize();this.json=json;this.prompts=prompts;
        try { Files.createDirectories(this.root); safe(this.root); } catch(Exception e) { throw error(); }
    }
    /** Verify existing delivery or atomically publish a new copy; never rewrite internal artifacts. */
    public Path export(Manifest manifest,PackageManifest source,Path internalDirectory) {
        try {
            Path target=safe(root.resolve("DELIVERY-"+UUID.fromString(manifest.deliveryId())));
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) { inspect(target,manifest,source);return target; }
            Path stage=Files.createTempDirectory(root,".delivery-");safe(stage);Files.createDirectory(stage.resolve("images"));
            for(int i=0;i<source.originals().size();i++) {
                var image=source.originals().get(i);Path original=internalDirectory.resolve("batches/BATCH-"+source.context().batchId()+"/original/"+image.fileName());
                if(!Files.isRegularFile(original,LinkOption.NOFOLLOW_LINKS)
                        || !original.toRealPath().startsWith(internalDirectory.toRealPath())) { throw error(); }
                Files.copy(original,safe(stage.resolve(manifest.assets().get(i).fileName())));
            }
            Files.write(stage.resolve("manifest.json"),json.writeValueAsBytes(manifest),StandardOpenOption.CREATE_NEW);
            Files.writeString(stage.resolve("prompt.md"),prompts.build(manifest),StandardOpenOption.CREATE_NEW);
            inspect(stage,manifest,source);
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) { throw error(); }
            Files.move(stage,target,StandardCopyOption.ATOMIC_MOVE);return target;
        } catch(Exception e) { throw error(); }
    }
    private void inspect(Path dir,Manifest expected,PackageManifest source) throws Exception {
        safe(dir);Set<String> actual=new HashSet<>();
        try(var paths=Files.walk(dir)) {
            for(Path path:paths.toList()) {
                safe(path);
                if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) { actual.add(dir.relativize(path).toString().replace('\\','/')); }
                else if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)) { throw error(); }
            }
        }
        Set<String> allowed=new HashSet<>(List.of("manifest.json","prompt.md"));expected.assets().forEach(a -> allowed.add(a.fileName()));
        if(!actual.equals(allowed)) { throw error(); }
        if(Files.size(dir.resolve("manifest.json"))>2*1024*1024 || Files.size(dir.resolve("prompt.md"))>2*1024*1024) { throw error(); }
        if(!expected.equals(json.readValue(Files.readAllBytes(safe(dir.resolve("manifest.json"))),Manifest.class))
                || !prompts.build(expected).equals(Files.readString(safe(dir.resolve("prompt.md")),StandardCharsets.UTF_8))) { throw error(); }
        for(int i=0;i<source.originals().size();i++) {
            Path file=safe(dir.resolve(expected.assets().get(i).fileName()));var original=source.originals().get(i);
            if(Files.size(file)!=original.bytes() || Files.size(file)>OriginalImageFetcher.MAX_BYTES
                    || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))).equals(original.sha256())) { throw error(); }
        }
    }
    private Path safe(Path path) {
        path=path.toAbsolutePath().normalize();if(!path.startsWith(root)) { throw error(); }
        try {
            for(Path p=path;p!=null && p.startsWith(root);p=p.getParent()) {
                if(Files.isSymbolicLink(p) || Files.exists(p) && !p.toRealPath().startsWith(root.toRealPath())) { throw error(); }
            }
            return path;
        } catch(Exception e) { throw error(); }
    }
    private BusinessException error() { return new BusinessException("MUSE_DELIVERY_INVALID","Delivery is missing, modified, or cannot be safely published"); }
}
