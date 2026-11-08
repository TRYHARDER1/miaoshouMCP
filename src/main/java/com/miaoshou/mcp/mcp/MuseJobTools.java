package com.miaoshou.mcp.mcp;

import com.miaoshou.mcp.domain.image.muse.MuseModels.*;
import com.miaoshou.mcp.service.muse.MuseJobService;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** Manual Muse handoff only; original export downloads never fetch result links. */
@Component
public class MuseJobTools {
    private final MuseJobService jobs;
    public MuseJobTools(MuseJobService jobs) { this.jobs=jobs; }
    @Tool(name="product_image_export_batch",description="Create/reuse an INTERNAL version-1 package with originals and private source URLs/SKU associations. For external Muse sharing use product_image_export_muse_delivery instead. Downloads only snapshot originals; never calls Muse.")
    public ExportView export(String batchId,@ToolParam(required=false) Integer version) { return jobs.export(batchId,version); }
    @Tool(name="product_image_mark_handed_off",description="Record explicit manual handoff to Muse. Independent of M5.2 processing leases; performs no remote submission.")
    public ResultsView handedOff(String batchId,@ToolParam(required=false) Integer version) { return jobs.handedOff(batchId,version); }
    @Tool(name="product_image_submit_results",description="Register 1–4 assetId/downloadUrl mappings for an exported batch. Partial submission allowed; conflicting links rejected. No result downloads, quality approval or uploads.")
    public ResultsView submit(String batchId,List<ResultInput> results,@ToolParam(required=false) Integer version) { return jobs.submit(batchId,results,version); }
    @Tool(name="product_image_get_batch_results",description="Read durable Muse link registrations, handoff state and missing assets. downloadVerified remains false. Repairs the local result-links projection if possible.")
    public ResultsView results(String batchId) { return jobs.results(batchId); }
}
