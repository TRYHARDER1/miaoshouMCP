package com.miaoshou.mcp.service.muse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.image.muse.MuseModels.*;
import com.miaoshou.mcp.repository.MuseJobMapper;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Manual export and registration with independent durable states; never claims M5.2 tasks. */
@Service
public class MuseJobService {
    private static final DateTimeFormatter TIME=new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final MuseJobMapper store;
    private final MuseBatchReader reader;
    private final MusePromptBuilder prompts;
    private final MusePackageFiles files;
    private final OriginalImageFetcher fetcher;
    private final SafeImageSource urls;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public MuseJobService(MuseJobMapper store,MuseBatchReader reader,MusePromptBuilder prompts,MusePackageFiles files,
                           OriginalImageFetcher fetcher,SafeImageSource urls,ObjectMapper json,Clock clock,PlatformTransactionManager manager) {
        this.store=store;this.reader=reader;this.prompts=prompts;this.files=files;this.fetcher=fetcher;
        this.urls=urls;this.json=json;this.clock=clock;transaction=new TransactionTemplate(manager);
    }

    /** Reserve once, acquire originals outside DB locks, then atomically publish a complete versioned package. */
    public ExportView export(String batchId,Integer version) {
        checkVersion(version);
        JobRow reserved=tx(() -> {
            Context context=reader.read(batchId,true); JobRow row=store.find(batchId);
            if (row==null) {
                row=new JobRow(UUID.randomUUID().toString(),batchId,1,HandoffStatus.CREATING,encode(context),null,
                        UUID.randomUUID().toString(),deadline(),null,now(),now()); store.insert(row); return row;
            }
            if (!context.equals(decode(row.contextJson(),Context.class))) { throw error("MUSE_CONTEXT_CHANGED"); }
            if (ready(row)) { return row; }
            var published=files.inspect(row);
            if (published!=null) {
                row=update(row,HandoffStatus.EXPORTED,encode(published),null,null,null); store.update(row); return row;
            }
            if (row.status()==HandoffStatus.CREATING && row.leaseUntil().compareTo(now())>0) { throw error("MUSE_EXPORT_BUSY"); }
            row=update(row,HandoffStatus.CREATING,null,UUID.randomUUID().toString(),deadline(),null); store.update(row); return row;
        });
        if (!ready(reserved)) {
            try {
                // A directory published immediately before a crashed DB commit can be reconciled without redownloading.
                PackageManifest recovered=files.inspect(reserved);
                Path stage=null;
                PackageManifest manifest=recovered;
                if (manifest==null) {
                    Context context=decode(reserved.contextJson(),Context.class); stage=files.stage(reserved);
                    List<OriginalFile> originals=new ArrayList<>();
                    for (var asset:context.assets()) { originals.add(files.original(stage,context,asset.assetId(),fetcher.fetch(asset))); }
                    manifest=new PackageManifest(reserved.jobId(),1,MusePromptBuilder.VERSION,context,originals); files.prepare(stage,manifest);
                }
                final Path staged=stage; final PackageManifest completed=manifest;
                tx(() -> {
                    JobRow current=store.find(batchId);
                    if (!Objects.equals(current.buildToken(),reserved.buildToken()) || current.status()!=HandoffStatus.CREATING
                            || current.leaseUntil().compareTo(now())<=0) { throw error("MUSE_EXPORT_ATTEMPT_STALE"); }
                    reader.read(batchId,true);
                    if (staged!=null) { files.publish(staged,current); }
                    store.update(update(current,HandoffStatus.EXPORTED,encode(completed),null,null,null)); return null;
                });
            } catch (BusinessException failure) {
                tx(() -> {
                    var current=store.find(batchId);
                    if (current.status()==HandoffStatus.CREATING && Objects.equals(current.buildToken(),reserved.buildToken())) {
                        store.update(update(current,HandoffStatus.EXPORT_FAILED,null,null,null,failure.code()));
                    }
                    return null;
                });
                throw failure;
            }
        }
        String sync=sync(batchId);
        return tx(() -> {
            JobRow row=requireReady(batchId); var manifest=files.inspect(row);
            if (manifest==null) { throw error("MUSE_PACKAGE_CORRUPT"); }
            return new ExportView(row.jobId(),batchId,row.version(),row.status(),manifest,prompts.build(manifest.context()),
                    files.directory(row).toString(),manifest.context().assets().size(),sync);
        });
    }

    /** Explicit human confirmation is independent of all M5.2 execution leases. */
    public ResultsView handedOff(String batchId,Integer version) {
        checkVersion(version);
        tx(() -> {
            reader.read(batchId,true); var row=requireReady(batchId);
            if (row.status()==HandoffStatus.EXPORTED) { store.update(update(row,HandoffStatus.WAITING_MUSE,row.packageJson(),null,null,null)); }
            return null;
        });
        return results(batchId);
    }

    /** Register an atomic partial set. No result URL is resolved, fetched or treated as quality approval. */
    public ResultsView submit(String batchId,List<ResultInput> results,Integer version) {
        checkVersion(version);
        if (results==null || results.isEmpty() || results.size()>4) { throw error("MUSE_RESULT_COUNT_INVALID"); }
        tx(() -> {
            Context context=reader.read(batchId,true); JobRow job=requireReady(batchId);
            Set<String> owned=new HashSet<>(); context.assets().forEach(a -> owned.add(a.assetId()));
            Set<String> inputIds=new HashSet<>();
            for (var input:results) {
                if (input==null || !owned.contains(input.assetId())) { throw error("MUSE_ASSET_NOT_IN_BATCH"); }
                if (!inputIds.add(input.assetId())) { throw error("MUSE_RESULT_DUPLICATE_ASSET"); }
                var uri=urls.parse(input.downloadUrl());
                String canonical="https://"+uri.getHost().toLowerCase(Locale.ROOT)
                        +(uri.getRawPath().isEmpty()?"/":uri.getRawPath())+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery());
                String hash=digest(canonical);
                ResultRow prior=store.byAsset(input.assetId());
                if (prior!=null) {
                    if (!prior.jobId().equals(job.jobId()) || !prior.downloadUrl().equals(input.downloadUrl())) { throw error("MUSE_RESULT_CONFLICT"); }
                    continue;
                }
                if (store.byUrl(hash)!=null) { throw error("MUSE_RESULT_URL_CONFLICT"); }
                store.insertResult(new ResultRow(job.jobId(),input.assetId(),input.downloadUrl(),hash,now(),"LINK_REGISTERED",false));
            }
            int count=store.results(job.jobId()).size();
            store.update(update(job,count==owned.size()?HandoffStatus.LINKS_RECEIVED:HandoffStatus.PARTIALLY_RECEIVED,
                    job.packageJson(),null,null,null)); return null;
        });
        return results(batchId);
    }

    /** Read durable registration, including the exact remaining asset IDs, and repair its derived file. */
    public ResultsView results(String batchId) {
        tx(() -> { reader.read(batchId,false); requireReady(batchId); return null; });
        String sync=sync(batchId);
        return tx(() -> {
            var job=requireReady(batchId); Context context=decode(job.contextJson(),Context.class);
            var rows=store.results(job.jobId()); Set<String> present=new HashSet<>(); rows.forEach(r -> present.add(r.assetId()));
            var missing=context.assets().stream().map(a -> a.assetId()).filter(id -> !present.contains(id)).toList();
            return new ResultsView(job.jobId(),batchId,job.status(),rows,missing,sync);
        });
    }

    private String sync(String batchId) {
        try {
            tx(() -> { var row=requireReady(batchId); files.results(row,store.results(row.jobId())); return null; });
            return "SYNCED";
        } catch (BusinessException e) { return "SYNC_PENDING"; }
    }
    private JobRow requireReady(String batchId) {
        var row=store.find(batchId);
        if (row==null || !ready(row)) { throw error("MUSE_JOB_NOT_EXPORTED"); }
        return row;
    }
    private boolean ready(JobRow row) { return row.status()!=HandoffStatus.CREATING && row.status()!=HandoffStatus.EXPORT_FAILED; }
    private JobRow update(JobRow r,HandoffStatus status,String payload,String token,String lease,String error) {
        return new JobRow(r.jobId(),r.batchId(),r.version(),status,r.contextJson(),payload,token,lease,error,r.createdAt(),now());
    }
    private <T> T tx(Supplier<T> body) {
        try { return transaction.execute(s -> { store.lock(); return body.get(); }); }
        catch (DataAccessException | TransactionException e) { throw error("MUSE_STORAGE_FAILED"); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw error("MUSE_DATA_INVALID"); }
    }
    private <T> T decode(String value,Class<T> type) {
        try { return json.readValue(value,type); } catch (Exception e) { throw error("MUSE_DATA_INVALID"); }
    }
    private String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw error("MUSE_DATA_INVALID"); }
    }
    private void checkVersion(Integer version) { if (version!=null && version!=1) { throw error("MUSE_JOB_VERSION_CONFLICT"); } }
    private String now() { return TIME.format(clock.instant()); }
    private String deadline() { return TIME.format(clock.instant().plusSeconds(900)); }
    private BusinessException error(String code) { return new BusinessException(code,"Muse request rejected: "+code); }
}
