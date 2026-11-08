package com.miaoshou.mcp.service.muse;

import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.image.processed.MuseProcessedModels.Source;
import com.miaoshou.mcp.domain.image.muse.MuseModels.PackageManifest;
import com.miaoshou.mcp.repository.MuseJobMapper;
import org.springframework.stereotype.Component;

/** Reconstruct an internal source from immutable registry/package data, never user URLs. */
@Component
public class MuseRegisteredResultReader {
    private final MuseJobMapper jobs;private final MuseBatchReader batches;private final MusePackageFiles files;
    public MuseRegisteredResultReader(MuseJobMapper jobs,MuseBatchReader batches,MusePackageFiles files) { this.jobs=jobs;this.batches=batches;this.files=files; }
    /** Existing published package and batch ownership, optionally enforcing current control state. */
    public PackageManifest manifest(String batchId,boolean eligible) {
        var context=batches.read(batchId,eligible);var job=jobs.find(batchId);
        if(job==null || job.packageJson()==null) { throw error("MUSE_JOB_NOT_EXPORTED"); }
        var manifest=files.inspect(job);
        if(manifest==null || !context.equals(manifest.context())) { throw error("PROCESSED_CONTEXT_INVALID"); }
        return manifest;
    }
    /** Return only a registered result belonging to this exact batch and frozen original. */
    public Source read(String batchId,String assetId,boolean eligible) {
        var manifest=manifest(batchId,eligible);var original=manifest.originals().stream().filter(f -> f.assetId().equals(assetId)).findFirst()
                .orElseThrow(() -> error("ASSET_NOT_IN_BATCH"));
        var result=jobs.byAsset(assetId);
        if(result==null) { throw error("RESULT_NOT_REGISTERED"); }
        if(!result.jobId().equals(manifest.jobId()) || !result.status().equals("LINK_REGISTERED") || result.downloadVerified()) { throw error("PROCESSED_CONTEXT_INVALID"); }
        try {
            var uri=java.net.URI.create(result.downloadUrl());
            String canonical="https://"+uri.getHost().toLowerCase(java.util.Locale.ROOT)
                    +(uri.getRawPath().isEmpty()?"/":uri.getRawPath())+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery());
            if(!ProcessedImageValidator.sha(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(result.urlHash())) { throw error("PROCESSED_SOURCE_CHANGED"); }
        } catch(BusinessException e) { throw e; } catch(Exception e) { throw error("PROCESSED_SOURCE_CHANGED"); }
        return new Source(jobs.find(batchId),result,original,manifest.originals().indexOf(original));
    }
    private BusinessException error(String code) { return new BusinessException(code,"Registered image context rejected: "+code); }
}
