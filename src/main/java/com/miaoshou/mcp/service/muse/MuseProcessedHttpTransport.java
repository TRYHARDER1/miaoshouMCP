package com.miaoshou.mcp.service.muse;

import com.miaoshou.mcp.common.BusinessException;
import io.netty.channel.ChannelOption;
import io.netty.resolver.*;
import io.netty.util.concurrent.*;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/** Bounded transport, pinned IP with original hostname, without proxy/redirect/retry fallback. */
@Component
public class MuseProcessedHttpTransport {
    /** @param status HTTP status @param type content type @param location redirect, never logged @param bytes bounded body */
    public record Response(int status,String type,String location,byte[] bytes) { }
    /** One authorized hop. Non-success bodies are cancelled after at most one bounded chunk. */
    public Response get(URI uri,InetSocketAddress pinned,Duration budget) {
        try(AddressResolverGroup<InetSocketAddress> resolver=new AddressResolverGroup<>() {
            protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
                return new AbstractAddressResolver<InetSocketAddress>(executor) {
                    protected boolean doIsResolved(InetSocketAddress a) { return false; }
                    private void check(InetSocketAddress a) { if(!a.getHostString().equalsIgnoreCase(pinned.getHostString()) || a.getPort()!=443) { throw failure("SOURCE_REJECTED"); } }
                    protected void doResolve(InetSocketAddress a,Promise<InetSocketAddress> p) { check(a);p.setSuccess(pinned); }
                    protected void doResolveAll(InetSocketAddress a,Promise<List<InetSocketAddress>> p) { check(a);p.setSuccess(List.of(pinned)); }
                };
            }
        }) {
            var client=secureClient().noProxy().resolver(resolver).followRedirect(false).disableRetry(true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,5000).responseTimeout(Duration.ofSeconds(10))
                    .httpResponseDecoder(d -> d.maxChunkSize(8192));
            return client.headers(h -> h.set("Accept","image/jpeg,image/png")).get().uri(uri.toString()).response((r,content) -> {
                int status=r.status().code();String type=r.responseHeaders().get("Content-Type"),location=r.responseHeaders().get("Location");
                String declared=r.responseHeaders().get("Content-Length");long length=declared==null?-1:Long.parseLong(declared);AtomicLong received=new AtomicLong();
                var chunks=content.asByteArray().handle((byte[] chunk,reactor.core.publisher.SynchronousSink<byte[]> sink) -> {
                    if(received.addAndGet(chunk.length)>ProcessedImageValidator.MAX_BYTES) { sink.error(failure("BODY_TOO_LARGE")); }
                    else { sink.next(chunk); }
                });
                if(status!=200) { return chunks.take(1).then(Mono.just(new Response(status,type,location,new byte[0]))); }
                if(length>ProcessedImageValidator.MAX_BYTES) { return chunks.take(1).then(Mono.error(failure("BODY_TOO_LARGE"))); }
                if(type==null || !(type.split(";")[0].trim().equalsIgnoreCase("image/png") || type.split(";")[0].trim().equalsIgnoreCase("image/jpeg"))) {
                    return chunks.take(1).then(Mono.error(failure("TYPE_UNSUPPORTED")));
                }
                return chunks.reduce(new ByteArrayOutputStream(),(out,chunk) -> { out.writeBytes(chunk);return out; })
                        .map(out -> new Response(status,type,null,out.toByteArray()));
            }).single().timeout(budget).block();
        } catch(BusinessException e) { throw e; }
        catch(Exception e) { throw failure("DOWNLOAD_TRANSFER_FAILED"); }
    }
    /** Production always uses the platform trust store and Reactor's HTTPS hostname verification. */
    protected HttpClient secureClient() { return HttpClient.newConnection().secure(); }
    private BusinessException failure(String code) { return new BusinessException(code,"Registered result transfer failed: "+code); }
}
