package com.miaoshou.mcp.mcp;

import com.miaoshou.mcp.domain.image.processed.MuseProcessedModels.*;
import com.miaoshou.mcp.service.muse.MuseProcessedImageService;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** Identifier-only tools. No URL/path/overwrite input and no visual quality claims. */
@Component
public class MuseProcessedImageTools {
    private final MuseProcessedImageService service;
    public MuseProcessedImageTools(MuseProcessedImageService service) { this.service=service; }
    @Tool(name="product_image_download_results",description="Download only database-registered Muse results for an existing batch. Optional assetIds selects at most four owned assets. Independent bounded technical validation; does not approve visual quality or alter prior stages.")
    public BatchView download(String batchId,@ToolParam(required=false) List<String> assetIds) { return service.download(batchId,assetIds); }
    @Tool(name="product_image_get_processed_results",description="Read independent result-processing state and local integrity. verifyLocal defaults true; false reports integrity unknown. No network. Visual review remains NOT_REVIEWED.")
    public BatchView get(String batchId,@ToolParam(required=false) Boolean verifyLocal) { return service.get(batchId,verifyLocal==null || verifyLocal); }
    @Tool(name="product_image_get_download_progress",description="Read last persisted result download/validation counts, missing links and unstarted assets. Counts are not visual approval or a fresh file-health guarantee.")
    public Progress progress(String batchId) { return service.progress(batchId); }
    @Tool(name="product_image_retry_download",description="Explicit version-bound retry of a transient FAILED registered result. Does not replace expired links, override published conflicts or erase files; at most three network retries.")
    public BatchView retry(String batchId,String assetId,long expectedRowVersion) { return service.retry(batchId,assetId,expectedRowVersion); }
    @Tool(name="product_image_recover_downloads",description="Recover existing local download stages or published receipts. Never starts a network download. Invalid or partial files are not treated as complete; prior stage state is untouched.")
    public BatchView recover(String batchId,@ToolParam(required=false) List<String> assetIds) { return service.recover(batchId,assetIds); }
}
