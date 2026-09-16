package com.cloudstorage.service.processor;

import com.cloudstorage.service.processor.impl.ThumbnailHandler;
import com.cloudstorage.service.storage.StorageProperties;
import com.cloudstorage.service.storage.StorageService;
import com.cloudstorage.service.storage.impl.LocalStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缩略图处理器单测：用本地存储实现，不依赖 MinIO。
 */
class ThumbnailHandlerTest {

    private Path root;
    private StorageService storage;
    private ThumbnailHandler handler;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("cs-thumb-test-");
        StorageProperties properties = new StorageProperties();
        properties.setLocalPath(root.toString());
        storage = new LocalStorageService(properties);
        handler = new ThumbnailHandler(storage);
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var paths = Files.walk(root)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // 清理失败不影响断言
                        }
                    });
        }
    }

    @Test
    @DisplayName("图片对象能生成缩略图并写回存储，结果里带 objectKey")
    void generatesThumbnail() throws IOException {
        String objectKey = "objects/ab/original.jpg";
        byte[] jpeg = sampleJpeg(800, 600);
        storage.put(objectKey, new ByteArrayInputStream(jpeg), jpeg.length, "image/jpeg");

        ProcessingContext context = ProcessingContext.builder()
                .taskId(1L)
                .fileId(2L)
                .contentType("image/jpeg")
                .objectKey(objectKey)
                .build();
        handler.handle(context);

        String thumbnailKey = "thumbnails/ab/original.jpg_256.jpg";
        assertThat(storage.exists(thumbnailKey)).isTrue();
        assertThat(storage.size(thumbnailKey)).isGreaterThan(0);
        assertThat(context.getResult()).contains(thumbnailKey);

        BufferedImage thumbnail = ImageIO.read(new ByteArrayInputStream(readAll(thumbnailKey)));
        assertThat(Math.max(thumbnail.getWidth(), thumbnail.getHeight())).isEqualTo(256);
    }

    @Test
    @DisplayName("非图片内容类型直接跳过，不算失败")
    void skipsNonImage() {
        ProcessingContext context = ProcessingContext.builder()
                .contentType("text/plain")
                .objectKey("objects/aa/note.txt")
                .build();
        handler.handle(context);
        assertThat(context.getResult()).isEqualTo("SKIPPED:not-image");
    }

    private byte[] sampleJpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private byte[] readAll(String objectKey) throws IOException {
        try (var in = storage.get(objectKey)) {
            return in.readAllBytes();
        }
    }
}
