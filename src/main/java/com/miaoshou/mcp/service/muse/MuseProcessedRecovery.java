package com.miaoshou.mcp.service.muse;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Startup marks only this stage's expired attempts; explicit recovery handles their local artifacts. */
@Component
public class MuseProcessedRecovery {
    private final MuseProcessedImageService service;
    public MuseProcessedRecovery(MuseProcessedImageService service) { this.service=service; }
    @EventListener(ApplicationReadyEvent.class)
    public void expire() { service.expire(); }
}
