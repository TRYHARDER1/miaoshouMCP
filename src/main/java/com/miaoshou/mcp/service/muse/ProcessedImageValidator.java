package com.miaoshou.mcp.service.muse;

import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.image.processed.MuseProcessedModels.Metadata;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Component;

/** Strict supported-container and decode integrity checks; never a visual quality assessment. */
@Component
public class ProcessedImageValidator {
    public static final String VERSION="m54-integrity-v1";
    public static final int MAX_BYTES=8*1024*1024;
    /** Validate unmodified JPEG/PNG bytes with size/pixel bounds and decoder warnings treated as failure. */
    public Metadata validate(byte[] bytes,String contentType) {
        if(bytes==null || bytes.length==0) { throw error("EMPTY_IMAGE"); }
        if(bytes.length>MAX_BYTES) { throw error("BODY_TOO_LARGE"); }
        String type=contentType==null?"":contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if(!Set.of("image/jpeg","image/png").contains(type)) { throw error("TYPE_UNSUPPORTED"); }
        boolean png=bytes.length>=8 && Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10});
        boolean jpeg=bytes.length>=4 && bytes[0]==(byte)255 && bytes[1]==(byte)216 && bytes[bytes.length-2]==(byte)255 && bytes[bytes.length-1]==(byte)217;
        if(!(png && type.equals("image/png") || jpeg && type.equals("image/jpeg"))) { throw error("IMAGE_CORRUPT"); }
        if(png) { png(bytes); }
        try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext()) { throw error("IMAGE_CORRUPT"); }
            var reader=readers.next();
            try {
                reader.addIIOReadWarningListener((r,warning) -> { throw error("IMAGE_DECODE_WARNING"); });
                reader.setInput(input,false,true);int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<=0 || height<=0 || width>20000 || height>20000 || (long)width*height>40000000) { throw error("IMAGE_DIMENSIONS_INVALID"); }
                if(reader.getNumImages(true)!=1 || reader.read(0)==null) { throw error("IMAGE_CORRUPT"); }
                return new Metadata(type,bytes.length,width,height,sha(bytes),png?"png":"jpg",VERSION);
            } finally { reader.dispose(); }
        } catch(BusinessException e) { throw e; }
        catch(Exception e) { throw error("IMAGE_CORRUPT"); }
    }
    private void png(byte[] bytes) {
        int position=8;boolean header=false,data=false,end=false,closedData=false,palette=false;
        while(position<bytes.length) {
            if(bytes.length-position<12) { throw error("IMAGE_CORRUPT"); }
            int length=ByteBuffer.wrap(bytes,position,4).getInt();
            if(length<0 || (long)position+12+length>bytes.length) { throw error("IMAGE_CORRUPT"); }
            String type=new String(bytes,position+4,4,StandardCharsets.US_ASCII);
            if(!type.matches("[A-Za-z]{4}")) { throw error("IMAGE_CORRUPT"); }
            CRC32 crc=new CRC32();crc.update(bytes,position+4,4+length);
            long expected=Integer.toUnsignedLong(ByteBuffer.wrap(bytes,position+8+length,4).getInt());
            if(crc.getValue()!=expected) { throw error("IMAGE_CRC_INVALID"); }
            if(!header && (!type.equals("IHDR") || length!=13)) { throw error("IMAGE_CORRUPT"); }
            switch(type) {
                case "IHDR" -> { if(header) { throw error("IMAGE_CORRUPT"); }header=true; }
                case "PLTE" -> { if(data || palette || length==0 || length%3!=0 || length>768) { throw error("IMAGE_CORRUPT"); }palette=true; }
                case "IDAT" -> { if(closedData) { throw error("IMAGE_CORRUPT"); }data=true; }
                case "IEND" -> { if(!data || length!=0 || position+12!=bytes.length) { throw error("IMAGE_CORRUPT"); }end=true; }
                default -> { if(Character.isUpperCase(type.charAt(0))) { throw error("TYPE_UNSUPPORTED"); } }
            }
            if(data && !type.equals("IDAT")) { closedData=true; }
            position+=12+length;
        }
        if(!end) { throw error("IMAGE_CORRUPT"); }
    }
    /** Content digest only, with no URL or identity reinterpretation. */
    public static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(Exception e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private BusinessException error(String code) { return new BusinessException(code,"Processed image integrity check failed: "+code); }
}
