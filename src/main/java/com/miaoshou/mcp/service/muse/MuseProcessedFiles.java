package com.miaoshou.mcp.service.muse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.image.processed.MuseProcessedModels.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Private per-asset directories with immutable receipts and atomic directory publication. */
@Component
public class MuseProcessedFiles {
    private final Path root;private final ObjectMapper json;
    public MuseProcessedFiles(@Value("${muse.jobs-root:.data/image-jobs}") String root,ObjectMapper json) {
        this.root=Path.of(root).toAbsolutePath().normalize();this.json=json;
        try { Files.createDirectories(this.root);safe(this.root); } catch(Exception e) { throw error("PROCESSED_FILE_IO_FAILED"); }
    }
    /** @param receipt complete transfer evidence @param bytes exact immutable bytes */
    public record Staged(Receipt receipt,byte[] bytes) { }
    public String original(Source s) { return safe(batch(s).resolve("original").resolve(s.original().fileName())).toString(); }
    public Path stage(Source s,String token) {
        id(token,"[a-f0-9-]{36}");return safe(job(s).resolve(".processed-staging").resolve(asset(s)).resolve(token));
    }
    public String relative(Path path) { return root.relativize(safe(path)).toString().replace('\\','/'); }
    public Path resolve(String relative) {
        if(relative==null || relative.contains("\\") || relative.contains(":") || Arrays.asList(relative.split("/")).contains("..")) { throw error("PROCESSED_FILE_CONFLICT"); }
        return safe(root.resolve(relative));
    }
    /** Save unchanged complete bytes and their transfer receipt under an exclusive token. */
    public Receipt saveDownload(Source s,String token,Download download,String now) {
        if(download.bytes()==null || download.bytes().length==0 || download.bytes().length>ProcessedImageValidator.MAX_BYTES) { throw error("BODY_TOO_LARGE"); }
        try {
            Path dir=stage(s,token);safe(dir);Files.createDirectories(dir);safe(dir);
            Metadata m=new Metadata(download.mediaType(),download.bytes().length,0,0,ProcessedImageValidator.sha(download.bytes()),"","download-v1");
            var receipt=receipt(s,token,m,now);write(dir.resolve("result.part"),download.bytes());write(dir.resolve("download.json"),json.writeValueAsBytes(receipt));return receipt;
        } catch(BusinessException e) { throw e; } catch(Exception e) { throw error("PROCESSED_FILE_IO_FAILED"); }
    }
    /** Prior complete data may be reused, but its immutable old token does not grant publication ownership. */
    public Staged loadDownload(Source s,Row r) {
        if(r.stagingRelativePath()==null) { return null; }
        Path dir=resolve(r.stagingRelativePath());
        if(!Files.exists(safe(dir.resolve("download.json")),LinkOption.NOFOLLOW_LINKS)) { return null; }
        try {
            if(!dir.startsWith(job(s).resolve(".processed-staging").resolve(asset(s)))) { throw error("PROCESSED_FILE_CONFLICT"); }
            var receipt=json.readValue(read(dir.resolve("download.json"),65536),Receipt.class);
            identity(s,receipt);if(!dir.getFileName().toString().equals(receipt.attemptToken())) { throw error("PROCESSED_FILE_CONFLICT"); }
            byte[] bytes=read(dir.resolve("result.part"),ProcessedImageValidator.MAX_BYTES);
            if(bytes.length!=receipt.metadata().actualBytes() || !ProcessedImageValidator.sha(bytes).equals(receipt.metadata().sha256())) { throw error("STAGED_FILE_CORRUPT"); }
            return new Staged(receipt,bytes);
        } catch(BusinessException e) { throw e; } catch(Exception e) { throw error("STAGED_FILE_CORRUPT"); }
    }
    /** Form a fresh fenced validation directory; old complete stages remain intact until new data is complete. */
    public Receipt prepare(Source s,String token,Download download,Metadata metadata,String now) {
        try {
            Path dir=stage(s,token);
            if(!Files.exists(dir.resolve("download.json"),LinkOption.NOFOLLOW_LINKS)) { saveDownload(s,token,download,now); }
            var result=receipt(s,token,metadata,now);
            write(dir.resolve(asset(s)+"."+metadata.extension()),download.bytes());
            write(dir.resolve("integrity.json"),json.writeValueAsBytes(result));return result;
        } catch(BusinessException e) { throw e; } catch(Exception e) { throw error("PROCESSED_FILE_IO_FAILED"); }
    }
    /** The final asset directory contains only the result and its receipt; no replacement is permitted. */
    public void publish(Source s,Row row,Receipt receipt) {
        identity(s,receipt);
        if(!receipt.attemptToken().equals(row.attemptToken())) { throw error("DOWNLOAD_LEASE_LOST"); }
        Path target=destination(s);Path staged=stage(s,row.attemptToken());
        try {
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) { throw error("PROCESSED_FILE_CONFLICT"); }
            Files.createDirectories(safe(target.getParent()));safe(target.getParent());
            // Publish an isolated pair, retaining the complete download stage for crash recovery.
            Path pair=safe(staged.resolve("published"));Files.createDirectory(pair);
            String name=asset(s)+"."+receipt.metadata().extension();
            write(pair.resolve(name),read(staged.resolve(name),ProcessedImageValidator.MAX_BYTES));
            write(pair.resolve("integrity.json"),json.writeValueAsBytes(receipt));
            Files.move(pair,target,StandardCopyOption.ATOMIC_MOVE);
        } catch(BusinessException e) { throw e; } catch(Exception e) { throw error("FILE_PUBLISH_FAILED"); }
    }
    /** Inspect exactly one published file/receipt pair and compare its bytes and frozen source identities. */
    public Receipt inspect(Source s,Row row) {
        Path dir=destination(s);if(!Files.exists(dir,LinkOption.NOFOLLOW_LINKS)) { return null; }
        try {
            var receipt=json.readValue(read(dir.resolve("integrity.json"),65536),Receipt.class);identity(s,receipt);
            Metadata m=receipt.metadata();
            if(row.attemptToken()==null || !row.attemptToken().equals(receipt.attemptToken()) || !ProcessedImageValidator.VERSION.equals(m.validatorVersion())
                    || !Set.of("jpg","png").contains(m.extension()) || m.width()<=0 || m.height()<=0 || m.width()>20000 || m.height()>20000
                    || (long)m.width()*m.height()>40000000 || !Set.of("image/jpeg","image/png").contains(m.mediaType())) { throw error("PROCESSED_FILE_CONFLICT"); }
            String name=asset(s)+"."+m.extension();
            try(var paths=Files.list(dir)) {
                if(!paths.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()).equals(Set.of(name,"integrity.json"))) { throw error("PROCESSED_FILE_CONFLICT"); }
            }
            byte[] bytes=read(dir.resolve(name),ProcessedImageValidator.MAX_BYTES);
            if(bytes.length!=m.actualBytes() || !ProcessedImageValidator.sha(bytes).equals(m.sha256())) { throw error("PUBLISHED_FILE_CORRUPT"); }
            if(row.processedSha256()!=null && !row.processedSha256().equals(m.sha256())) { throw error("PUBLISHED_FILE_CORRUPT"); }
            if(row.processedRelativePath()!=null && (!row.processedRelativePath().equals(relative(dir.resolve(name)))
                    || !Objects.equals(row.processedSha256(),m.sha256()) || !Objects.equals(row.actualBytes(),m.actualBytes())
                    || !Objects.equals(row.width(),m.width()) || !Objects.equals(row.height(),m.height()) || !Objects.equals(row.mediaType(),m.mediaType()))) { throw error("PUBLISHED_FILE_CORRUPT"); }
            return receipt;
        } catch(BusinessException e) { throw e; } catch(Exception e) { throw error("PUBLISHED_FILE_CORRUPT"); }
    }
    public String publishedPath(Source s,Receipt r) { return relative(destination(s).resolve(asset(s)+"."+r.metadata().extension())); }
    public byte[] publishedBytes(Source s,Receipt r) { try { return read(resolve(publishedPath(s,r)),ProcessedImageValidator.MAX_BYTES); } catch(Exception e) { throw error("PUBLISHED_FILE_CORRUPT"); } }
    private Receipt receipt(Source s,String token,Metadata m,String now) { return new Receipt(s.job().jobId(),s.job().batchId(),asset(s),s.result().urlHash(),s.original().sha256(),token,m,now); }
    private void identity(Source s,Receipt r) {
        if(!s.job().jobId().equals(r.jobId()) || !s.job().batchId().equals(r.batchId()) || !asset(s).equals(r.assetId())
                || !s.result().urlHash().equals(r.sourceUrlHash()) || !s.original().sha256().equals(r.originalSha256())) { throw error("PROCESSED_FILE_CONFLICT"); }
    }
    private Path destination(Source s) {
        if(s.position()<0 || s.position()>3) { throw error("PROCESSED_FILE_CONFLICT"); }
        return safe(batch(s).resolve("processed").resolve("A"+(s.position()+1)));
    }
    private String asset(Source s) { return id(s.result().assetId(),"IMG-[a-f0-9]{64}"); }
    private Path job(Source s) { return safe(root.resolve("JOB-"+id(s.job().jobId(),"[a-f0-9-]{36}"))); }
    private Path batch(Source s) { return safe(job(s).resolve("batches/BATCH-"+id(s.job().batchId(),"[A-Za-z0-9_-]{1,120}"))); }
    private String id(String value,String pattern) { if(value==null || !value.matches(pattern)) { throw error("PROCESSED_FILE_CONFLICT"); }return value; }
    private byte[] read(Path path,int limit) throws Exception {
        path=safe(path);
        try(var channel=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))) {
            if(channel.size()>limit) { throw error("BODY_TOO_LARGE"); }
            var out=new java.io.ByteArrayOutputStream();ByteBuffer buffer=ByteBuffer.allocate(8192);int n;
            while((n=channel.read(buffer))!=-1) { if(out.size()+n>limit) { throw error("BODY_TOO_LARGE"); }out.write(buffer.array(),0,n);buffer.clear(); }
            return out.toByteArray();
        }
    }
    private void write(Path path,byte[] bytes) throws Exception {
        try(var channel=Files.newByteChannel(safe(path),Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining()) { channel.write(data); }
            if(channel instanceof FileChannel file) { file.force(true); }
        }
    }
    private Path safe(Path path) {
        path=path.toAbsolutePath().normalize();if(!path.startsWith(root)) { throw error("PROCESSED_FILE_CONFLICT"); }
        try {
            for(Path p=path;p!=null && p.startsWith(root);p=p.getParent()) {
                if(Files.isSymbolicLink(p) || Files.exists(p) && !p.toRealPath().startsWith(root.toRealPath())) { throw error("PROCESSED_FILE_CONFLICT"); }
            }
            return path;
        } catch(BusinessException e) { throw e; } catch(Exception e) { throw error("PROCESSED_FILE_IO_FAILED"); }
    }
    private BusinessException error(String code) { return new BusinessException(code,"Processed file operation failed: "+code); }
}
