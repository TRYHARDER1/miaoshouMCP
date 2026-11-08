package com.miaoshou.mcp.service.muse;

import com.miaoshou.mcp.common.BusinessException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ProcessedImageValidatorTest {
    final ProcessedImageValidator validator=new ProcessedImageValidator();
    static byte[] image(String format,int color) throws Exception {
        var image=new BufferedImage(3,4,BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,color);
        var out=new ByteArrayOutputStream();ImageIO.write(image,format,out);return out.toByteArray();
    }
    @ParameterizedTest @ValueSource(strings={"jpeg","png"})
    void actualDecodeAndExactDigest(String format) throws Exception {
        byte[] bytes=image(format,0xff0000);var m=validator.validate(bytes,"image/"+format);
        assertThat(m.width()).isEqualTo(3);assertThat(m.height()).isEqualTo(4);assertThat(m.actualBytes()).isEqualTo(bytes.length);
        assertThat(m.sha256()).isEqualTo(ProcessedImageValidator.sha(bytes));assertThat(m.validatorVersion()).isEqualTo("m54-integrity-v1");
    }
    @Test void rejectsEmptyNonImageUnsupportedAndOversize() throws Exception {
        assertThatThrownBy(() -> validator.validate(new byte[0],"image/png")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.validate("<html>wrong</html>".getBytes(),"image/jpeg")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.validate(image("png",0),"image/webp")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.validate(image("png",0),"image/jpeg")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.validate(new byte[ProcessedImageValidator.MAX_BYTES+1],"image/png")).isInstanceOf(BusinessException.class);
    }
    @Test void rejectsTruncationAndAppendedPngPayload() throws Exception {
        for(String format:List.of("png","jpeg")) {
            byte[] bytes=image(format,0);assertThatThrownBy(() -> validator.validate(Arrays.copyOf(bytes,bytes.length-2),"image/"+format)).isInstanceOf(BusinessException.class);
        }
        byte[] png=image("png",0);assertThatThrownBy(() -> validator.validate(Arrays.copyOf(png,png.length+1),"image/png")).isInstanceOf(BusinessException.class);
    }
    @Test void rejectsBadCrcAndPixelBombBeforeDecode() throws Exception {
        byte[] bytes=image("png",0);bytes[29]^=1;
        assertThatThrownBy(() -> validator.validate(bytes,"image/png")).isInstanceOf(BusinessException.class).extracting(e -> ((BusinessException)e).code()).isEqualTo("IMAGE_CRC_INVALID");
        byte[] large=image("png",0);ByteBuffer.wrap(large,16,8).putInt(20001).putInt(20001);
        CRC32 crc=new CRC32();crc.update(large,12,17);ByteBuffer.wrap(large,29,4).putInt((int)crc.getValue());
        assertThatThrownBy(() -> validator.validate(large,"image/png")).isInstanceOf(BusinessException.class).extracting(e -> ((BusinessException)e).code()).isEqualTo("IMAGE_DIMENSIONS_INVALID");
    }
}
