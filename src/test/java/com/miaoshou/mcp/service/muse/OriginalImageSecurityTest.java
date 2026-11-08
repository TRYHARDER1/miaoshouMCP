package com.miaoshou.mcp.service.muse;

import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.image.ImageAsset;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class OriginalImageSecurityTest {
    @ParameterizedTest
    @ValueSource(strings={"0.0.0.0","127.0.0.1","10.0.0.1","172.16.0.1","192.168.1.1","169.254.169.254",
            "100.100.100.200","168.63.129.16","192.0.2.1","198.18.0.1","198.51.100.1","203.0.113.1","224.0.0.1",
            "::1","fe80::1","fd00::1","2001:db8::1","2002:7f00:1::1","::ffff:127.0.0.1"})
    void blocksPrivateReservedAndMetadata(String address) throws Exception {
        var policy=new SafeImageSource();
        try { assertThat(policy.isPublic(InetAddress.getByName(address))).isFalse(); }
        finally { policy.close(); }
    }
    @ParameterizedTest
    @ValueSource(strings={"http://example.com/a.jpg","https://localhost/a","https://example.internal/a","https://127.1/a",
            "https://2130706433/a","https://example.com:444/a","https://user:secret@example.com/a","https://[::1]/a","", "file:///a"})
    void rejectsUnsafeUrlBeforeTransport(String url) {
        var policy=new SafeImageSource();
        try { assertThatThrownBy(() -> policy.parse(url)).isInstanceOf(BusinessException.class).hasMessageNotContaining("secret"); }
        finally { policy.close(); }
    }
    @Test
    void allDnsAnswersAreCheckedAndPinnedAddressRetainsHostname() throws Exception {
        var bad=new SafeImageSource() {
            protected InetAddress[] lookup(String host) throws UnknownHostException {
                return new InetAddress[]{InetAddress.getByName("8.8.8.8"),InetAddress.getByName("127.0.0.1")};
            }
        };
        try { assertThatThrownBy(() -> bad.resolve(URI.create("https://example.com/a"))).isInstanceOf(BusinessException.class); }
        finally { bad.close(); }
        var good=new SafeImageSource() {
            protected InetAddress[] lookup(String host) throws UnknownHostException { return new InetAddress[]{InetAddress.getByName("8.8.8.8")}; }
        };
        try {
            var pinned=good.resolve(URI.create("https://example.com/a"));
            assertThat(pinned.getAddress().getHostAddress()).isEqualTo("8.8.8.8");
            assertThat(pinned.getHostString()).isEqualTo("example.com");
        } finally { good.close(); }
    }
    static byte[] png() throws Exception {
        var out=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2,3,BufferedImage.TYPE_INT_RGB),"png",out); return out.toByteArray();
    }
    @Test
    void validatesActualContentNotJustMimeOrExtension() throws Exception {
        var policy=new SafeImageSource(); var fetcher=new OriginalImageFetcher(policy);
        try {
            var image=fetcher.validate(png(),"image/png"); assertThat(image.width()).isEqualTo(2); assertThat(image.height()).isEqualTo(3);
            assertThatThrownBy(() -> fetcher.validate(png(),"image/jpeg")).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> fetcher.validate("<html>not a photo</html>".getBytes(),"image/png")).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> fetcher.validate(new byte[OriginalImageFetcher.MAX_BYTES+1],"image/jpeg")).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> fetcher.validate(png(),"image/webp")).isInstanceOf(BusinessException.class);
        } finally { policy.close(); }
    }
    @Test
    void redirectsAreRevalidatedAndBounded() throws Exception {
        AtomicInteger resolutions=new AtomicInteger();
        var policy=new SafeImageSource() {
            public InetSocketAddress resolve(URI uri) { resolutions.incrementAndGet(); return new InetSocketAddress("8.8.8.8",443); }
        };
        var asset=new ImageAsset("IMG-"+"a".repeat(64),"https://example.com/a",List.of(),0,List.of(),null,ImageAsset.Status.PENDING);
        try {
            var downgrade=new OriginalImageFetcher(policy) {
                protected Response exchange(URI uri,InetSocketAddress pinned) { return new Response(302,"http://127.0.0.1/secret",null,null); }
            };
            assertThatThrownBy(() -> downgrade.fetch(asset)).isInstanceOf(BusinessException.class);
            assertThat(resolutions.get()).isEqualTo(1);
            resolutions.set(0);
            var loop=new OriginalImageFetcher(policy) {
                protected Response exchange(URI uri,InetSocketAddress pinned) { return new Response(302,"/again",null,null); }
            };
            assertThatThrownBy(() -> loop.fetch(asset)).isInstanceOf(BusinessException.class);
            assertThat(resolutions.get()).isEqualTo(4);
        } finally { policy.close(); }
    }
    @Test
    void emptyTransportResponseIsAnExplicitBusinessFailure() {
        var policy=new SafeImageSource() {
            public InetSocketAddress resolve(URI uri) { return new InetSocketAddress("8.8.8.8",443); }
        };
        try {
            var fetcher=new OriginalImageFetcher(policy) {
                protected Response exchange(URI uri,InetSocketAddress pinned) { return null; }
            };
            var asset=new ImageAsset("IMG-"+"a".repeat(64),"https://example.com/a",List.of(),0,List.of(),null,ImageAsset.Status.PENDING);
            assertThatThrownBy(() -> fetcher.fetch(asset)).isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException)e).code()).isEqualTo("ORIGINAL_CONTENT_REJECTED");
        } finally { policy.close(); }
    }
}
