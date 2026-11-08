package com.miaoshou.mcp.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Startup recovery respects active leases and never automatically repeats a task. */
@Component
public class ImageBatchRecovery {
    private final ImageBatchService batches;
    public ImageBatchRecovery(ImageBatchService batches) { this.batches = batches; }

    /** Recover only expired processing attempts after database initialization. */
    @EventListener(ApplicationReadyEvent.class)
    public void recover() { batches.recover(); }
}
