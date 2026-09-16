package com.cloudstorage.service.processor;

import com.cloudstorage.service.processor.impl.ExifHandler;
import com.cloudstorage.service.storage.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EXIF 处理器单测。
 * 关键是手写一段最小 TIFF（真实 EXIF 结构：II 头 + IFD0 里的 Make/Model），
 * 直接喂给解析方法，验证走的是真实的元数据解析路径而不是 mock。
 */
class ExifHandlerTest {

    private final ExifHandler handler = new ExifHandler(mock(StorageService.class), new ObjectMapper());

    @Test
    @DisplayName("能从真实 EXIF/TIFF 结构中解析出厂商与机型")
    void parsesMakeAndModelFromTiff() throws Exception {
        byte[] tiff = minimalTiff("TestCam", "ModelX");

        Map<String, Object> result = handler.parse(new ByteArrayInputStream(tiff));

        assertThat(result).containsEntry("make", "TestCam");
        assertThat(result).containsEntry("model", "ModelX");
    }

    @Test
    @DisplayName("格式不被识别时记为 NONE，不抛异常（避免正常情况被打成死信）")
    void treatsUnsupportedFormatAsNone() {
        StorageService storage = mock(StorageService.class);
        when(storage.get(anyString()))
                .thenReturn(new ByteArrayInputStream("definitely not an image".getBytes(StandardCharsets.UTF_8)));
        ExifHandler exifHandler = new ExifHandler(storage, new ObjectMapper());

        ProcessingContext context = ProcessingContext.builder()
                .fileId(1L)
                .contentType("image/webp")
                .objectKey("objects/aa/broken.webp")
                .build();
        exifHandler.handle(context);

        assertThat(context.getResult()).startsWith("NONE");
    }

    @Test
    @DisplayName("非图片类型直接跳过")
    void skipsNonImage() {
        ProcessingContext context = ProcessingContext.builder()
                .contentType("application/pdf")
                .objectKey("objects/aa/doc.pdf")
                .build();
        handler.handle(context);
        assertThat(context.getResult()).isEqualTo("SKIPPED:not-image");
    }

    /** 构造最小 TIFF：II 头 + IFD0(2 个 ASCII 条目: Make/Model) + 字符串数据区 */
    private byte[] minimalTiff(String make, String model) {
        byte[] makeBytes = (make + "\0").getBytes(StandardCharsets.US_ASCII);
        byte[] modelBytes = (model + "\0").getBytes(StandardCharsets.US_ASCII);
        int ifdOffset = 8;
        int entryCount = 2;
        int dataOffset = ifdOffset + 2 + entryCount * 12 + 4;

        ByteBuffer buffer = ByteBuffer.allocate(dataOffset + makeBytes.length + modelBytes.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 'I').put((byte) 'I');
        buffer.putShort((short) 42);
        buffer.putInt(ifdOffset);
        buffer.putShort((short) entryCount);
        // tag 0x010F = Make
        buffer.putShort((short) 0x010F).putShort((short) 2)
                .putInt(makeBytes.length).putInt(dataOffset);
        // tag 0x0110 = Model
        buffer.putShort((short) 0x0110).putShort((short) 2)
                .putInt(modelBytes.length).putInt(dataOffset + makeBytes.length);
        buffer.putInt(0);
        buffer.put(makeBytes).put(modelBytes);
        return buffer.array();
    }
}
