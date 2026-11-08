package com.miaoshou.mcp.service.muse;

import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.image.ImageAsset;
import io.netty.channel.ChannelOption;
import io.netty.resolver.*;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import java.net.*;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/** Only downloads originals already tied to a validated batch. Result links never use this component. */
@Component
public class OriginalImageFetcher {
    public static final int MAX_BYTES=8*1024*1024;
    private final SafeImageSource policy;
    public OriginalImageFetcher(SafeImageSource policy) { this.policy=policy; }
    /** @param bytes unchanged source bytes @param mediaType validated type @param extension safe suffix
     * @param width decoded width @param height decoded height */
    public record ImageBytes(byte[] bytes,String mediaType,String extension,int width,int height) { }
    /** @param status HTTP status @param location untrusted redirect location
     * @param type response MIME @param body size-limited bytes */
    protected record Response(int status,String location,String type,byte[] body) { }

    /** HTTPS only, bounded redirects and transport, followed by actual image decoding. */
    public ImageBytes fetch(ImageAsset asset) {
        URI uri=policy.parse(asset.originalUrl());
        for (int hop=0;hop<=3;hop++) {
            InetSocketAddress address=policy.resolve(uri);
            Response response=exchange(uri,address);
            if (response==null) { throw error("ORIGINAL_CONTENT_REJECTED"); }
            if (response.status()>=300 && response.status()<400) {
                if (hop==3 || response.location()==null) { throw error("ORIGINAL_REDIRECT_REJECTED"); }
                try { uri=policy.parse(uri.resolve(response.location()).toString()); }
                catch (Exception e) { throw error("ORIGINAL_REDIRECT_REJECTED"); }
            } else {
                if (response.status()!=200) { throw error("ORIGINAL_HTTP_FAILED"); }
                return validate(response.body(),response.type());
            }
        }
        throw error("ORIGINAL_REDIRECT_REJECTED");
    }

    /** Connection is pinned to the previously vetted address; automatic redirects/retry/proxy are disabled. */
    protected Response exchange(URI uri,InetSocketAddress pinned) {
        try (AddressResolverGroup<InetSocketAddress> resolver=new AddressResolverGroup<>() {
            @Override protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
                return new AbstractAddressResolver<InetSocketAddress>(executor) {
                    protected boolean doIsResolved(InetSocketAddress a) { return false; }
                    private void check(InetSocketAddress a) {
                        if (!a.getHostString().equalsIgnoreCase(pinned.getHostString()) || a.getPort()!=443) {
                            throw error("ORIGINAL_SOURCE_REJECTED");
                        }
                    }
                    protected void doResolve(InetSocketAddress a,Promise<InetSocketAddress> p) { check(a); p.setSuccess(pinned); }
                    protected void doResolveAll(InetSocketAddress a,Promise<List<InetSocketAddress>> p) { check(a); p.setSuccess(List.of(pinned)); }
                };
            }
        }) {
            var client=HttpClient.newConnection().resolver(resolver).noProxy().followRedirect(false).disableRetry(true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,5000).responseTimeout(Duration.ofSeconds(10)).secure();
            return WebClient.builder().clientConnector(new ReactorClientHttpConnector(client))
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_BYTES)).build()
                    .get().uri(uri).header("Accept","image/jpeg,image/png").exchangeToMono(r -> {
                        int status=r.statusCode().value();
                        String location=r.headers().asHttpHeaders().getFirst("Location");
                        String type=r.headers().asHttpHeaders().getFirst("Content-Type");
                        if (status!=200) { return r.releaseBody().thenReturn(new Response(status,location,type,new byte[0])); }
                        if (r.headers().contentLength().orElse(0)>MAX_BYTES || !supported(type)) {
                            return r.releaseBody().then(Mono.error(error("ORIGINAL_CONTENT_REJECTED")));
                        }
                        return r.bodyToMono(byte[].class).switchIfEmpty(Mono.error(error("ORIGINAL_CONTENT_REJECTED")))
                                .map(body -> new Response(status,null,type,body));
                    }).timeout(Duration.ofSeconds(20)).block();
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw error("ORIGINAL_FETCH_FAILED"); }
    }

    /** Verify MIME, signature, dimensions and decoded content before publishing unchanged bytes. */
    public ImageBytes validate(byte[] bytes,String type) {
        if (bytes==null || bytes.length<8 || bytes.length>MAX_BYTES || !supported(type)) { throw error("ORIGINAL_CONTENT_REJECTED"); }
        String mime=type.split(";")[0].trim().toLowerCase(Locale.ROOT);
        boolean png=bytes[0]==(byte)137 && bytes[1]==80 && bytes[2]==78 && bytes[3]==71
                && bytes[4]==13 && bytes[5]==10 && bytes[6]==26 && bytes[7]==10;
        boolean jpeg=bytes[0]==(byte)255 && bytes[1]==(byte)216 && bytes[2]==(byte)255;
        if (!(png && mime.equals("image/png") || jpeg && mime.equals("image/jpeg"))) { throw error("ORIGINAL_CONTENT_REJECTED"); }
        try (var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);
            if (!readers.hasNext()) { throw error("ORIGINAL_CONTENT_REJECTED"); }
            var reader=readers.next();
            try {
                reader.setInput(input,true,true); int width=reader.getWidth(0),height=reader.getHeight(0);
                if (width<1 || height<1 || width>20000 || height>20000 || (long)width*height>40000000
                        || reader.read(0)==null) { throw error("ORIGINAL_CONTENT_REJECTED"); }
                return new ImageBytes(bytes,mime,png?"png":"jpg",width,height);
            } finally { reader.dispose(); }
        } catch (Exception e) { throw error("ORIGINAL_CONTENT_REJECTED"); }
    }
    private boolean supported(String type) {
        if (type==null) { return false; }
        String value=type.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return value.equals("image/jpeg") || value.equals("image/png");
    }
    private BusinessException error(String code) { return new BusinessException(code,"Original image request rejected: "+code); }
}
