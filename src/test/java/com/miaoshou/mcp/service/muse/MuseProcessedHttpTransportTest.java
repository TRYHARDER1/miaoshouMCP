package com.miaoshou.mcp.service.muse;

import com.miaoshou.mcp.common.BusinessException;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import reactor.netty.http.client.HttpClient;
import static org.assertj.core.api.Assertions.*;

/** Real TLS/HTTP protocol fixtures. No production allow-private or trust-all switch is introduced. */
class MuseProcessedHttpTransportTest {
    static SSLContext serverContext;static X509Certificate certificate;
    @BeforeAll static void certificate() throws Exception {
        Path dir=Files.createTempDirectory("m54-tls-");Path store=dir.resolve("fixture.p12");String password=UUID.randomUUID().toString();
        String binary=System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool";
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",binary).toString(),"-genkeypair","-alias","fixture","-keyalg","RSA",
                "-keysize","2048","-validity","1","-dname","CN=example.com","-ext","SAN=dns:example.com","-storetype","PKCS12",
                "-keystore",store.toString(),"-storepass",password,"-keypass",password,"-noprompt")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if(!process.waitFor(20,TimeUnit.SECONDS)) { process.destroyForcibly();throw new IllegalStateException("Fixture certificate generation timed out"); }
        assertThat(process.exitValue()).isZero();KeyStore keys=KeyStore.getInstance("PKCS12");
        try(var input=Files.newInputStream(store)) { keys.load(input,password.toCharArray()); }
        certificate=(X509Certificate)keys.getCertificate("fixture");KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,password.toCharArray());
        serverContext=SSLContext.getInstance("TLS");serverContext.init(km.getKeyManagers(),null,null);
    }
    MuseProcessedHttpTransport trustedFixtureClient() throws Exception {
        var trusted=SslContextBuilder.forClient().trustManager(certificate).build();
        return new MuseProcessedHttpTransport() {
            protected HttpClient secureClient() {
                return HttpClient.newConnection().secure(s -> s.sslContext(trusted).handlerConfigurator(h -> {
                    var p=h.engine().getSSLParameters();p.setEndpointIdentificationAlgorithm("HTTPS");h.engine().setSSLParameters(p);
                }));
            }
        };
    }
    @FunctionalInterface interface ResponseBody { void write(OutputStream out) throws Exception; }
    MuseProcessedHttpTransport.Response exchange(MuseProcessedHttpTransport client,String host,String headers,ResponseBody body) throws Exception {
        try(var server=(SSLServerSocket)serverContext.getServerSocketFactory().createServerSocket(0,1,InetAddress.getLoopbackAddress());var pool=Executors.newSingleThreadExecutor()) {
            var accepted=new CountDownLatch(1);
            var future=pool.submit(() -> {
                try(var socket=(SSLSocket)server.accept()) {
                    accepted.countDown();socket.setSoTimeout(5000);socket.startHandshake();
                    var input=socket.getInputStream();int state=0,b;
                    while((b=input.read())!=-1) { state=switch(state) { case 0 -> b==13?1:0;case 1 -> b==10?2:0;case 2 -> b==13?3:0;default -> b==10?4:0; };if(state==4) { break; } }
                    var out=socket.getOutputStream();out.write(headers.getBytes(StandardCharsets.US_ASCII));out.flush();body.write(out);out.flush();
                } catch(Exception ignored) { /* expected on TLS rejection or cancellation of an oversized/irrelevant response */ }
            });
            try {
                var pin=new InetSocketAddress(InetAddress.getByAddress(host,InetAddress.getLoopbackAddress().getAddress()),server.getLocalPort());
                return client.get(URI.create("https://"+host+"/fixture?token=TLS_PRIVATE_SENTINEL"),pin,Duration.ofSeconds(10));
            } finally {
                boolean connected=accepted.await(10,TimeUnit.SECONDS);server.close();future.get(10,TimeUnit.SECONDS);assertThat(connected).isTrue();
            }
        }
    }
    @Test void realTlsAndChunkedImageBody() throws Exception {
        byte[] png=ProcessedImageValidatorTest.image("png",0);
        var r=exchange(trustedFixtureClient(),"example.com","HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n",out -> {
            out.write((Integer.toHexString(png.length)+"\r\n").getBytes(StandardCharsets.US_ASCII));out.write(png);out.write("\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        });
        assertThat(r.bytes()).isEqualTo(png);
    }
    @Test void untrustedCertificateAndHostnameMismatchAreRejectedWithoutLeakingQuery() throws Exception {
        for(var entry:List.of(Map.entry(new MuseProcessedHttpTransport(),"example.com"),Map.entry(trustedFixtureClient(),"wrong.example.com"))) {
            assertThatThrownBy(() -> exchange(entry.getKey(),entry.getValue(),"",out -> {})).isInstanceOf(BusinessException.class).hasMessageNotContaining("TLS_PRIVATE_SENTINEL");
        }
    }
    @Test void actualChunkedOverrunStopsAtBodyLimit() throws Exception {
        assertThatThrownBy(() -> exchange(trustedFixtureClient(),"example.com","HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nTransfer-Encoding: chunked\r\n\r\n",out -> {
            byte[] block=new byte[16384];for(int i=0;i<600;i++) { out.write("4000\r\n".getBytes(StandardCharsets.US_ASCII));out.write(block);out.write("\r\n".getBytes(StandardCharsets.US_ASCII));out.flush(); }
            out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        })).isInstanceOf(BusinessException.class).extracting(e -> ((BusinessException)e).code()).isEqualTo("BODY_TOO_LARGE");
    }
    @Test void irrelevantErrorBodyIsCancelledInsteadOfDrainingEntireStream() throws Exception {
        var r=exchange(trustedFixtureClient(),"example.com","HTTP/1.1 503 Unavailable\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\n\r\n",out -> {
            byte[] block=new byte[16384];for(int i=0;i<600;i++) { out.write("4000\r\n".getBytes(StandardCharsets.US_ASCII));out.write(block);out.write("\r\n".getBytes(StandardCharsets.US_ASCII));out.flush(); }
        });
        assertThat(r.status()).isEqualTo(503);assertThat(r.bytes()).isEmpty();
    }
}
