package com.miaoshou.mcp.service.muse;

import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.image.processed.MuseProcessedModels.*;
import com.miaoshou.mcp.repository.MuseJobMapper;
import com.miaoshou.mcp.repository.MuseProcessedResultMapper;
import java.time.Clock;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Independent, fenced per-asset processing; network and decoding never hold the SQLite transaction. */
@Service
public class MuseProcessedImageService {
    private static final DateTimeFormatter TIME=new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final MuseProcessedResultMapper store;private final MuseJobMapper jobs;private final MuseRegisteredResultReader reader;
    private final MuseProcessedFiles files;private final MuseProcessedImageFetcher fetcher;private final ProcessedImageValidator validator;
    private final Clock clock;private final TransactionTemplate tx;private final Semaphore capacity=new Semaphore(2);
    /** @param row fenced state @param local complete local bytes or null @param error safe refusal or null @param done no further work */
    private record Work(Row row,Download local,String error,boolean done,Receipt published) {
        private Work(Row row,Download local,String error,boolean done) { this(row,local,error,done,null); }
    }
    public MuseProcessedImageService(MuseProcessedResultMapper store,MuseJobMapper jobs,MuseRegisteredResultReader reader,
                                      MuseProcessedFiles files,MuseProcessedImageFetcher fetcher,ProcessedImageValidator validator,
                                      Clock clock,PlatformTransactionManager manager) {
        this.store=store;this.jobs=jobs;this.reader=reader;this.files=files;this.fetcher=fetcher;this.validator=validator;this.clock=clock;tx=new TransactionTemplate(manager);
    }
    /** Download registered assets only; failures are per asset and never erase successful neighbors. */
    public BatchView download(String batchId,List<String> assetIds) { return operate(batchId,assetIds,"DOWNLOAD",null); }
    /** Explicit local-only recovery, including a complete directory published before a failed DB commit. */
    public BatchView recover(String batchId,List<String> assetIds) { return operate(batchId,assetIds,"RECOVER",null); }
    /** Explicit, version-bound network retry. Never changes registered links or removes conflicting files. */
    public BatchView retry(String batchId,String assetId,long expectedVersion) {
        if(assetId==null || assetId.isBlank()) { throw error("ASSET_SELECTION_INVALID"); }
        if(expectedVersion<0) { throw error("ROW_VERSION_REQUIRED"); }
        return operate(batchId,List.of(assetId),"RETRY",expectedVersion);
    }
    private BatchView operate(String batchId,List<String> ids,String mode,Long expected) {
        var manifest=reader.manifest(batchId,!mode.equals("RECOVER"));
        Set<String> owned=new LinkedHashSet<>();manifest.context().assets().forEach(a -> owned.add(a.assetId()));
        List<String> selected;
        if(ids==null) { selected=jobs.results(manifest.jobId()).stream().map(r -> r.assetId()).toList(); }
        else {
            if(ids.isEmpty() || ids.size()>4 || new HashSet<>(ids).size()!=ids.size() || !owned.containsAll(ids)) { throw error("ASSET_SELECTION_INVALID"); }
            selected=List.copyOf(ids);
        }
        Map<String,String> errors=new HashMap<>();
        for(String id:selected) {
            try { process(batchId,id,mode,expected); }
            catch(BusinessException e) { errors.put(id,e.code()); }
        }
        return view(batchId,true,errors);
    }
    private void process(String batchId,String assetId,String mode,Long expected) {
        if(!capacity.tryAcquire()) { throw error("DOWNLOAD_CAPACITY_BUSY"); }
        Source source=null;Row owner=null;String phase="CONTEXT";boolean owns=false;
        try {
            source=reader.read(batchId,assetId,!mode.equals("RECOVER"));final Source s=source;
            Work work=transaction(() -> reserve(s,mode,expected));owner=work.row();
            if(work.error()!=null) { throw error(work.error()); }
            if(work.done()) { return; }
            owns=true;
            if(work.published()!=null) {
                phase="VALIDATE";
                Metadata checked=validator.validate(work.local().bytes(),work.local().mediaType());
                if(!checked.equals(work.published().metadata())) { throw error("PUBLISHED_FILE_CORRUPT"); }
                Row expectedRow=owner;
                transaction(() -> {
                    Row current=store.find(s.job().jobId(),assetId);binding(s,current);
                    if(current.rowVersion()!=expectedRow.rowVersion() || !Objects.equals(current.attemptToken(),expectedRow.attemptToken())) { throw error("DOWNLOAD_LEASE_LOST"); }
                    Receipt receipt=files.inspect(s,current);
                    if(!work.published().equals(receipt)) { throw error("PUBLISHED_FILE_CORRUPT"); }
                    return event(current,store.validated(current,checked,files.publishedPath(s,receipt),now()),"RECOVERED_PUBLISHED");
                });
                return;
            }
            phase="FETCH";Download data=work.local();
            if(data==null) {
                data=fetcher.fetch(source);var receipt=files.saveDownload(source,owner.attemptToken(),data,now());
                Row before=owner;
                owner=transaction(() -> { Row current=owned(s,before.attemptToken());return event(current,
                        store.downloaded(current,receipt.metadata(),files.relative(files.stage(s,current.attemptToken())),now()),"DOWNLOADED"); });
            }
            phase="VALIDATE";Metadata metadata=validator.validate(data.bytes(),data.mediaType());
            Receipt completed=files.prepare(source,owner.attemptToken(),data,metadata,now());
            Row before=owner;
            owner=transaction(() -> { Row current=owned(s,before.attemptToken());return event(current,
                    store.validating(current,files.relative(files.stage(s,current.attemptToken())),now()),"VALIDATING"); });
            phase="PUBLISH";String token=owner.attemptToken();
            transaction(() -> {
                reader.read(batchId,assetId,true);Row current=owned(s,token);binding(s,current);
                files.publish(s,current,completed);
                return event(current,store.validated(current,completed.metadata(),files.publishedPath(s,completed),now()),"VALIDATED");
            });
        } catch(BusinessException failure) {
            if(owns && source!=null && owner!=null && owner.attemptToken()!=null) {
                final Source s=source;final String token=owner.attemptToken(),at=phase;
                try {
                    transaction(() -> {
                        Row current=store.find(s.job().jobId(),assetId);
                        if(current!=null && Objects.equals(token,current.attemptToken()) && active(current)) {
                            return fail(current,failure.code(),at);
                        }
                        return current;
                    });
                } catch(BusinessException ignored) { /* original error remains safe; committed files can be recovered */ }
            }
            throw failure;
        } finally { capacity.release(); }
    }
    private Work reserve(Source s,String mode,Long expected) {
        Row r=store.find(s.job().jobId(),s.result().assetId());
        if(r==null) {
            if(mode.equals("RECOVER")) { return new Work(null,null,"PROCESSING_NOT_STARTED",true); }
            if(mode.equals("RETRY")) { return new Work(null,null,"PROCESSING_NOT_STARTED",true); }
            store.insert(s,now());r=store.find(s.job().jobId(),s.result().assetId());store.event(r,null,"CREATED",now());
        }
        binding(s,r);
        if(expected!=null && (r.rowVersion()!=expected || r.status()!=State.FAILED)) { return new Work(r,null,"ROW_VERSION_CONFLICT",true); }
        try {
            Receipt published=files.inspect(s,r);
            if(published!=null) {
                if(r.status()!=State.VALIDATED) {
                    return new Work(r,new Download(files.publishedBytes(s,published),published.metadata().mediaType(),200),null,false,published);
                }
                return new Work(r,null,null,true);
            }
            if(r.status()==State.VALIDATED || r.processedRelativePath()!=null) {
                Row failed=fail(r,"PUBLISHED_FILE_MISSING","RECHECK");return new Work(failed,null,"PUBLISHED_FILE_MISSING",true);
            }
        } catch(BusinessException e) {
            Row failed=fail(r,e.code(),"RECHECK");return new Work(failed,null,e.code(),true);
        }
        if(active(r)) {
            if(r.leaseUntil().compareTo(now())>0) { return new Work(r,null,"DOWNLOAD_BUSY",true); }
            r=fail(r,"RECOVERY_REQUIRED","RECOVER");
        }
        if(r.status()==State.FAILED && mode.equals("DOWNLOAD")) { return new Work(r,null,"EXPLICIT_RETRY_REQUIRED",true); }
        if(mode.equals("RETRY") && (r.retryCount()>=3 || !retryable(r.errorCode()))) { return new Work(r,null,"DOWNLOAD_NOT_RETRYABLE",true); }
        MuseProcessedFiles.Staged local=null;
        if(!mode.equals("DOWNLOAD")) {
            try { local=files.loadDownload(s,r); }
            catch(BusinessException e) { if(mode.equals("RECOVER")) { return new Work(fail(r,e.code(),"RECOVER"),null,e.code(),true); } }
        }
        if(mode.equals("RECOVER") && local==null) {
            if(r.status()!=State.PENDING && r.status()!=State.FAILED) { r=fail(r,"INTERRUPTED","RECOVER"); }
            return new Work(r,null,"NO_COMPLETE_LOCAL_DATA",true);
        }
        reader.read(s.job().batchId(),s.result().assetId(),true);
        if(store.liveCount(now())>=2) { return new Work(r,null,"DOWNLOAD_CAPACITY_BUSY",true); }
        String token=UUID.randomUUID().toString();boolean network=local==null;
        r=event(r,store.claim(r,network?State.DOWNLOADING:State.DOWNLOADED,token,lease(),
                network?files.relative(files.stage(s,token)):r.stagingRelativePath(),mode.equals("RETRY") && network?1:0,network,now()),network?"CLAIMED":"RESUMED_LOCAL");
        if(local!=null) {
            r=event(r,store.downloaded(r,local.receipt().metadata(),r.stagingRelativePath(),now()),"LOCAL_BYTES_READY");
            return new Work(r,new Download(local.bytes(),local.receipt().metadata().mediaType(),200),null,false);
        }
        return new Work(r,null,null,false);
    }
    /** Query with optional local health checking. Verification never initiates a network request. */
    public BatchView get(String batchId,boolean verifyLocal) { return view(batchId,verifyLocal,Map.of()); }
    public Progress progress(String batchId) { return get(batchId,false).progress(); }
    private BatchView view(String batchId,boolean verify,Map<String,String> operationErrors) {
        return transaction(() -> {
            var manifest=reader.manifest(batchId,false);var registered=jobs.results(manifest.jobId());Set<String> present=new HashSet<>();registered.forEach(r -> present.add(r.assetId()));
            List<View> views=new ArrayList<>();List<String> missing=new ArrayList<>();int notStarted=0;int[] counts=new int[State.values().length];
            for(var asset:manifest.context().assets()) {
                if(!present.contains(asset.assetId())) { missing.add(asset.assetId());continue; }
                Source source=reader.read(batchId,asset.assetId(),false);Row r=store.find(manifest.jobId(),asset.assetId());
                String health="UNKNOWN";boolean valid=false;
                if(r!=null) {
                    binding(source,r);
                    if(verify && r.status()==State.VALIDATED) {
                        try { var receipt=files.inspect(source,r);if(receipt==null) { throw error("PUBLISHED_FILE_MISSING"); }health="OK";valid=true; }
                        catch(BusinessException e) { r=fail(r,e.code(),"RECHECK");health=e.code().contains("MISSING")?"MISSING":"CORRUPT"; }
                    }
                    counts[r.status().ordinal()]++;
                } else { notStarted++; }
                Metadata metadata=r==null || r.width()==null || r.height()==null || r.processedSha256()==null?null:
                        new Metadata(r.mediaType(),r.actualBytes(),r.width(),r.height(),r.processedSha256(),"image/png".equals(r.mediaType())?"png":"jpg",r.validatorVersion());
                boolean duplicate=r!=null && r.processedSha256()!=null && store.duplicates(r.processedSha256(),r.assetId())>0;
                views.add(new View(asset.assetId(),r==null?"NOT_STARTED":r.status().name(),r==null?0:r.rowVersion(),r==null?0:r.attemptNo(),r==null?0:r.retryCount(),
                        valid,"NOT_REVIEWED",health,metadata,r==null || r.processedRelativePath()==null?null:files.resolve(r.processedRelativePath()).toString(),files.original(source),
                        operationErrors.getOrDefault(asset.assetId(),r==null?null:r.errorCode()),duplicate,r==null?null:r.validatedAt(),r==null?null:r.updatedAt()));
            }
            var p=new Progress(manifest.context().assets().size(),registered.size(),missing.size(),notStarted,counts[0],counts[1],counts[2],counts[3],counts[4],counts[5]);
            return new BatchView(batchId,views,missing,p);
        });
    }
    /** A bounded startup metadata sweep. No file deletion, download or M5.2 lease changes. */
    public int expire() {
        return transaction(() -> { var expired=store.expired(now());for(Row r:expired) { fail(r,"RECOVERY_REQUIRED","RECOVER"); }return expired.size(); });
    }
    /** Internal lease renewal only; caller must still own a live attempt. */
    public Row heartbeat(String batchId,String assetId,String token) {
        Source s=reader.read(batchId,assetId,false);
        return transaction(() -> { Row r=owned(s,token);return event(r,store.heartbeat(r,lease(),now()),"HEARTBEAT"); });
    }
    private Row owned(Source s,String token) {
        Row r=store.find(s.job().jobId(),s.result().assetId());
        if(r==null || !Objects.equals(token,r.attemptToken()) || !active(r) || r.leaseUntil().compareTo(now())<=0) { throw error("DOWNLOAD_LEASE_LOST"); }
        return r;
    }
    private void binding(Source s,Row r) {
        if(!s.result().urlHash().equals(r.sourceUrlHash()) || !s.original().sha256().equals(r.originalSha256())) { throw error("PROCESSED_SOURCE_CHANGED"); }
    }
    private boolean active(Row r) { return r.status()==State.DOWNLOADING || r.status()==State.DOWNLOADED || r.status()==State.VALIDATING; }
    private Row fail(Row r,String code,String phase) {
        Integer http=code.matches("HTTP_STATUS_[1-5][0-9][0-9]")?Integer.valueOf(code.substring(12)):null;
        return event(r,store.fail(r,code,phase,http,now()),"FAILED");
    }
    private Row event(Row before,int count,String code) {
        if(count!=1) { throw error("DOWNLOAD_LEASE_LOST"); }
        Row after=store.find(before.jobId(),before.assetId());store.event(after,before.status(),code,now());return after;
    }
    private boolean retryable(String code) {
        return code!=null && (Set.of("DOWNLOAD_TRANSFER_FAILED","DOWNLOAD_TIMEOUT","INTERRUPTED","RECOVERY_REQUIRED","FILE_PUBLISH_FAILED","PROCESSED_FILE_IO_FAILED","STAGED_FILE_CORRUPT").contains(code)
                || code.matches("HTTP_STATUS_5[0-9][0-9]"));
    }
    private <T> T transaction(Supplier<T> body) {
        try { return tx.execute(s -> { store.lock();return body.get(); }); }
        catch(DataAccessException | TransactionException e) { throw error("M54_STORAGE_FAILED"); }
    }
    private String now() { return TIME.format(clock.instant()); }
    private String lease() { return TIME.format(clock.instant().plusSeconds(300)); }
    private BusinessException error(String code) { return new BusinessException(code,"Processed image request rejected: "+code); }
}
