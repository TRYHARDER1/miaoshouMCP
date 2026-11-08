package com.miaoshou.mcp.service.muse;

import com.miaoshou.mcp.common.BusinessException;
import jakarta.annotation.PreDestroy;
import java.net.*;
import java.util.Locale;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

/** Public HTTPS validation and bounded DNS resolution; no credentials or remote errors are logged. */
@Component
public class SafeImageSource {
    private final ExecutorService dns=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),
            r -> { Thread t=new Thread(r,"muse-original-dns"); t.setDaemon(true); return t; },new ThreadPoolExecutor.AbortPolicy());

    /** Local registration check only: does not fetch or resolve result URLs. */
    public URI parse(String url) {
        try {
            if (url==null || url.isBlank() || url.length()>8192) { throw rejected(); }
            URI uri=URI.create(url); String host=uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host==null || uri.getRawUserInfo()!=null
                    || (uri.getPort()!=-1 && uri.getPort()!=443)) { throw rejected(); }
            host=host.replace("[","").replace("]","").toLowerCase(Locale.ROOT);
            String name=host.endsWith(".")?host.substring(0,host.length()-1):host;
            if (name.equals("localhost") || name.endsWith(".localhost") || name.endsWith(".local")
                    || name.endsWith(".internal") || name.endsWith(".lan") || name.endsWith(".home")
                    || !name.contains(".") && !name.contains(":")) { throw rejected(); }
            if (name.contains(":") || name.matches("[0-9.]+")) {
                if (!isPublic(InetAddress.getByName(name))) { throw rejected(); }
            }
            return uri;
        } catch (Exception e) { throw rejected(); }
    }

    /** Check every returned address, then pin the chosen IP while retaining the TLS host identity. */
    public InetSocketAddress resolve(URI uri) {
        parse(uri.toString()); Future<InetAddress[]> future=null;
        try {
            String host=uri.getHost().replace("[","").replace("]","");
            future=dns.submit(() -> lookup(host));
            InetAddress[] addresses=future.get(4,TimeUnit.SECONDS);
            if (addresses.length==0) { throw rejected(); }
            for (var address:addresses) { if (!isPublic(address)) { throw rejected(); } }
            return new InetSocketAddress(InetAddress.getByAddress(host,addresses[0].getAddress()),443);
        } catch (Exception e) { throw new BusinessException("ORIGINAL_SOURCE_REJECTED","Original source resolution rejected or timed out"); }
        finally { if (future!=null) { future.cancel(true); } }
    }
    /** Resolution seam used inside the bounded DNS executor, never after IP validation. */
    protected InetAddress[] lookup(String host) throws UnknownHostException { return InetAddress.getAllByName(host); }

    /** Conservative public-address policy, including reserved and metadata-specific exclusions. */
    public boolean isPublic(InetAddress ip) {
        if (ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress()
                || ip.isSiteLocalAddress() || ip.isMulticastAddress()) { return false; }
        byte[] b=ip.getAddress();
        if (b.length==4) {
            int a=b[0]&255,c=b[1]&255,d=b[2]&255;
            return a!=0 && a!=10 && a!=127 && a<224
                    && !(a==100 && c>=64 && c<=127) && !(a==169 && c==254)
                    && !(a==172 && c>=16 && c<=31) && !(a==192 && (c==168 || c==0 || c==88 && d==99))
                    && !(a==198 && (c==18 || c==19 || c==51 && d==100))
                    && !(a==203 && c==0 && d==113)
                    && !ip.getHostAddress().equals("168.63.129.16");
        }
        // Only ordinary global unicast; reject documentation, 6to4 and Teredo transition ranges.
        return (b[0]&0xe0)==0x20 && !((b[0]&255)==0x20 && (b[1]&255)==2)
                && !((b[0]&255)==0x20 && (b[1]&255)==1 && (b[2]&255)==0 && (b[3]&255)==0)
                && !((b[0]&255)==0x20 && (b[1]&255)==1 && (b[2]&255)==13 && (b[3]&255)==184);
    }
    private BusinessException rejected() { return new BusinessException("IMAGE_URL_REJECTED","Expected an eligible public HTTPS image URL"); }
    @PreDestroy public void close() { dns.shutdownNow(); }
}
