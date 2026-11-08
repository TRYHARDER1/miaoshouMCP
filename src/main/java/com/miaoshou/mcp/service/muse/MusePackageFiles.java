package com.miaoshou.mcp.service.muse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.image.muse.MuseModels.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Controlled local packages; mutable link files are database projections, not authoritative records. */
@Component
public class MusePackageFiles {
    private final Path root;
    private final ObjectMapper json;
    private final MusePromptBuilder prompts;
    public MusePackageFiles(@Value("${muse.jobs-root:.data/image-jobs}") String directory,
                            ObjectMapper json,MusePromptBuilder prompts) {
        root=Path.of(directory).toAbsolutePath().normalize(); this.json=json; this.prompts=prompts;
        try { Files.createDirectories(root); secure(root); }
        catch (Exception e) { throw error(); }
    }

    /** Create an isolated staging directory for one fenced export attempt. */
    public Path stage(JobRow job) {
        var path=root.resolve(".staging").resolve(token(job.jobId())+"-"+token(job.buildToken()));
        try { secure(path); Files.createDirectories(path); secure(path); return path; }
        catch (Exception e) { throw error(); }
    }
    /** Exact final job path; callers cannot supply a filesystem destination. */
    public Path directory(JobRow job) { return secure(root.resolve("JOB-"+token(job.jobId()))); }
    private Path batchDir(Path directory,String batchId) {
        if (batchId==null || !batchId.matches("[A-Za-z0-9_-]{1,120}")) { throw error(); }
        return secure(directory.resolve("batches").resolve("BATCH-"+batchId));
    }

    /** Write a source file with a name derived only from validated assetId and verified format. */
    public OriginalFile original(Path stage,Context context,String assetId,OriginalImageFetcher.ImageBytes image) {
        if (!assetId.matches("IMG-[a-f0-9]{64}") || !(image.extension().equals("jpg") || image.extension().equals("png"))) { throw error(); }
        String name=assetId+"."+image.extension();
        try {
            Path originals=batchDir(stage,context.batchId()).resolve("original");
            secure(originals); Files.createDirectories(originals); secure(originals);
            Files.write(secure(originals.resolve(name)),image.bytes(),StandardOpenOption.CREATE_NEW);
            return new OriginalFile(assetId,name,image.mediaType(),image.bytes().length,image.width(),image.height(),digest(image.bytes()));
        } catch (Exception e) { throw error(); }
    }

    /** Complete all immutable artifacts before the directory becomes visible as an export. */
    public void prepare(Path stage,PackageManifest manifest) {
        try {
            Path batch=batchDir(stage,manifest.context().batchId()); Files.createDirectories(batch);
            byte[] metadata=json.writeValueAsBytes(manifest);
            byte[] prompt=prompts.build(manifest.context()).getBytes(StandardCharsets.UTF_8);
            if (metadata.length>2*1024*1024 || prompt.length>2*1024*1024) {
                throw new BusinessException("MUSE_PACKAGE_TOO_LARGE","Package metadata exceeds local artifact limit");
            }
            write(stage.resolve("job.json"),metadata);
            write(batch.resolve("manifest.json"),metadata);
            write(batch.resolve("prompt.md"),prompt);
            write(batch.resolve("result-links.json"),"[]".getBytes(StandardCharsets.UTF_8));
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw error(); }
    }

    /** Publish using an atomic same-root directory rename; never replace an existing job. */
    public void publish(Path stage,JobRow job) {
        try { secure(stage); Files.move(stage,directory(job),StandardCopyOption.ATOMIC_MOVE); }
        catch (Exception e) { throw error(); }
    }

    /** Validate published context, immutable artifacts and every original's byte digest. */
    public PackageManifest inspect(JobRow job) {
        Path directory=directory(job);
        if (!Files.exists(directory,LinkOption.NOFOLLOW_LINKS)) { return null; }
        try {
            var manifest=json.readValue(read(directory.resolve("job.json"),2*1024*1024),PackageManifest.class);
            Context context=json.readValue(job.contextJson(),Context.class);
            if (!manifest.jobId().equals(job.jobId()) || manifest.version()!=job.version()
                    || !MusePromptBuilder.VERSION.equals(manifest.templateVersion()) || !context.equals(manifest.context())
                    || manifest.originals().size()!=context.assets().size()) { throw error(); }
            if (job.packageJson()!=null && !manifest.equals(json.readValue(job.packageJson(),PackageManifest.class))) { throw error(); }
            Path batch=batchDir(directory,job.batchId());
            if (!manifest.equals(json.readValue(read(batch.resolve("manifest.json"),2*1024*1024),PackageManifest.class))
                    || !prompts.build(context).equals(new String(read(batch.resolve("prompt.md"),2*1024*1024),StandardCharsets.UTF_8))) { throw error(); }
            for (int i=0;i<manifest.originals().size();i++) {
                var file=manifest.originals().get(i); String id=context.assets().get(i).assetId();
                if (!file.assetId().equals(id) || !(file.fileName().equals(id+".jpg") || file.fileName().equals(id+".png"))) { throw error(); }
                byte[] bytes=read(batch.resolve("original").resolve(file.fileName()),OriginalImageFetcher.MAX_BYTES);
                if (bytes.length!=file.bytes() || !digest(bytes).equals(file.sha256())) { throw error(); }
            }
            return manifest;
        } catch (Exception e) { throw new BusinessException("MUSE_PACKAGE_CORRUPT","Published package is missing or modified; it was not replaced"); }
    }

    /** Rebuild result-links.json from committed records using an atomic file replacement. */
    public void results(JobRow job,List<ResultRow> rows) {
        try {
            Path batch=batchDir(directory(job),job.batchId());
            if (!Files.isDirectory(batch)) { throw error(); }
            Path temp=secure(batch.resolve("links-"+UUID.randomUUID()+".tmp"));
            write(temp,json.writeValueAsBytes(rows));
            Files.move(temp,secure(batch.resolve("result-links.json")),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { throw error(); }
    }

    private byte[] read(Path path,int max) throws Exception {
        path=secure(path);
        if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>max) { throw error(); }
        return Files.readAllBytes(path);
    }
    private void write(Path path,byte[] bytes) throws Exception { Files.write(secure(path),bytes,StandardOpenOption.CREATE_NEW); }
    private String token(String value) {
        if (value==null || !value.matches("[a-f0-9-]{36}")) { throw error(); }
        return value;
    }
    private Path secure(Path path) {
        path=path.toAbsolutePath().normalize();
        if (!path.startsWith(root)) { throw error(); }
        try {
            for (Path current=path;current!=null && current.startsWith(root);current=current.getParent()) {
                if (Files.isSymbolicLink(current)) { throw error(); }
                if (Files.exists(current) && !current.toRealPath().startsWith(root.toRealPath())) { throw error(); }
            }
            return path;
        } catch (Exception e) { throw error(); }
    }
    private String digest(byte[] data) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
    private BusinessException error() { return new BusinessException("MUSE_EXPORT_IO_FAILED","Controlled image package file operation failed"); }
}
