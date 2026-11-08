package com.miaoshou.mcp.service.muse;

import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.image.processed.MuseProcessedModels.*;
import java.net.URI;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Fetch only a reader-authorized registry entry. No public arbitrary-URL tool exists. */
@Component
public class MuseProcessedImageFetcher {
    private final SafeImageSource policy;private final MuseProcessedHttpTransport transport;
    public MuseProcessedImageFetcher(SafeImageSource policy,MuseProcessedHttpTransport transport) { this.policy=policy;this.transport=transport; }
    /** Revalidate every redirect, with a monotonic overall network budget. */
    public Download fetch(Source source) {
        long deadline=System.nanoTime()+Duration.ofSeconds(120).toNanos();URI uri=policy.parse(source.result().downloadUrl());
        for(int hop=0;hop<=3;hop++) {
            if(deadline-System.nanoTime()<Duration.ofSeconds(4).toNanos()) { throw failure("DOWNLOAD_TIMEOUT"); }
            var address=policy.resolve(uri);long remaining=deadline-System.nanoTime();
            if(remaining<=0) { throw failure("DOWNLOAD_TIMEOUT"); }
            var r=transport.get(uri,address,Duration.ofNanos(Math.min(remaining,Duration.ofSeconds(20).toNanos())));
            if(r==null) { throw failure("EMPTY_IMAGE"); }
            if(r.status()>=300 && r.status()<400) {
                if(hop==3 || r.location()==null) { throw failure("REDIRECT_REJECTED"); }
                try { uri=policy.parse(uri.resolve(r.location()).toString()); } catch(Exception e) { throw failure("REDIRECT_REJECTED"); }
            } else {
                if(r.status()!=200) { throw failure("HTTP_STATUS_"+r.status()); }
                if(r.bytes()==null || r.bytes().length==0) { throw failure("EMPTY_IMAGE"); }
                if(r.bytes().length>ProcessedImageValidator.MAX_BYTES) { throw failure("BODY_TOO_LARGE"); }
                return new Download(r.bytes(),r.type(),r.status());
            }
        }
        throw failure("REDIRECT_REJECTED");
    }
    private BusinessException failure(String code) { return new BusinessException(code,"Registered result download failed: "+code); }
}
