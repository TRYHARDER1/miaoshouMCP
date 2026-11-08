package com.miaoshou.mcp.service.muse;

import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.image.muse.MuseModels.*;
import com.miaoshou.mcp.domain.image.processed.MuseProcessedModels.*;
import java.net.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MuseProcessedFetcherTest {
    Source source() {
        return new Source(null,new ResultRow("job","asset","https://example.com/registered?token=PRIVATE","hash","now","LINK_REGISTERED",false),null,0);
    }
    SafeImageSource policy() { return new SafeImageSource() { public InetSocketAddress resolve(URI u) { return new InetSocketAddress("8.8.8.8",443); } }; }
    @Test void exactRegisteredUrlAndSafeRedirectAreUsed() throws Exception {
        var policy=policy();var transport=mock(MuseProcessedHttpTransport.class);byte[] png=ProcessedImageValidatorTest.image("png",0);
        when(transport.get(any(),any(),any())).thenReturn(new MuseProcessedHttpTransport.Response(302,null,"https://example.net/final",new byte[0]))
                .thenReturn(new MuseProcessedHttpTransport.Response(200,"image/png",null,png));
        try {
            var result=new MuseProcessedImageFetcher(policy,transport).fetch(source());assertThat(result.bytes()).isEqualTo(png);
            verify(transport).get(eq(URI.create(source().result().downloadUrl())),any(),any(Duration.class));
            verify(transport).get(eq(URI.create("https://example.net/final")),any(),any(Duration.class));
        } finally { policy.close(); }
    }
    @Test void downgradeAndPrivateRedirectAreRejectedWithoutSecondRequest() {
        for(String location:new String[]{"http://example.com/a","https://127.0.0.1/private"}) {
            var policy=policy();var transport=mock(MuseProcessedHttpTransport.class);
            when(transport.get(any(),any(),any())).thenReturn(new MuseProcessedHttpTransport.Response(302,null,location,new byte[0]));
            try { assertThatThrownBy(() -> new MuseProcessedImageFetcher(policy,transport).fetch(source())).isInstanceOf(BusinessException.class).hasMessageNotContaining("PRIVATE");verify(transport,times(1)).get(any(),any(),any()); }
            finally { policy.close(); }
        }
    }
    @Test void httpErrorsEmptyAndRedirectLoopsRemainExplicitAndSanitized() {
        for(int status:new int[]{206,403,404,500}) {
            var policy=policy();var transport=mock(MuseProcessedHttpTransport.class);
            when(transport.get(any(),any(),any())).thenReturn(new MuseProcessedHttpTransport.Response(status,"image/png",null,new byte[0]));
            try { assertThatThrownBy(() -> new MuseProcessedImageFetcher(policy,transport).fetch(source())).isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException)e).code()).isEqualTo("HTTP_STATUS_"+status); }
            finally { policy.close(); }
        }
        var policy=policy();var transport=mock(MuseProcessedHttpTransport.class);
        when(transport.get(any(),any(),any())).thenReturn(new MuseProcessedHttpTransport.Response(302,null,"/loop",new byte[0]));
        try { assertThatThrownBy(() -> new MuseProcessedImageFetcher(policy,transport).fetch(source())).isInstanceOf(BusinessException.class);verify(transport,times(4)).get(any(),any(),any()); }
        finally { policy.close(); }
    }
}
