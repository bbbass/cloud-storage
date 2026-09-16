package com.cloudstorage.service.storage;

import com.cloudstorage.common.exception.BusinessException;
import com.cloudstorage.service.storage.impl.LocalStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本地存储实现的单元测试：不依赖 MinIO/MySQL，覆盖读写、合并顺序与路径越权。
 */
class LocalStorageServiceTest {

    private Path root;
    private LocalStorageService storage;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("cs-local-storage-");
        StorageProperties properties = new StorageProperties();
        properties.setLocalPath(root.toString());
        storage = new LocalStorageService(properties);
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var paths = Files.walk(root)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 清理失败不影响断言
                }
            });
        }
    }

    @Test
    @DisplayName("写入后可以读回，且大小与内容一致")
    void putThenGetRoundTrip() throws IOException {
        String content = "hello cloud storage";
        storage.put("objects/ab/demo.txt", new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                content.length(), "text/plain");

        assertThat(storage.exists("objects/ab/demo.txt")).isTrue();
        assertThat(storage.size("objects/ab/demo.txt")).isEqualTo(content.length());
        try (InputStream in = storage.get("objects/ab/demo.txt")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(content);
        }
    }

    @Test
    @DisplayName("compose 按传入顺序拼接分片")
    void composeKeepsPartOrder() throws IOException {
        storage.put("staging/1/1", new ByteArrayInputStream("aaa".getBytes(StandardCharsets.UTF_8)), 3);
        storage.put("staging/1/2", new ByteArrayInputStream("bbb".getBytes(StandardCharsets.UTF_8)), 3);
        storage.put("staging/1/3", new ByteArrayInputStream("c".getBytes(StandardCharsets.UTF_8)), 1);

        storage.compose("objects/cc/merged.txt", List.of("staging/1/1", "staging/1/2", "staging/1/3"), "text/plain");

        assertThat(storage.size("objects/cc/merged.txt")).isEqualTo(7);
        try (InputStream in = storage.get("objects/cc/merged.txt")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("aaabbbc");
        }
    }

    @Test
    @DisplayName("objectKey 越权访问被拒绝")
    void rejectsPathTraversal() {
        assertThatThrownBy(() -> storage.put("../escape.txt",
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法");

        assertThatThrownBy(() -> storage.get("..\\escape.txt"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("读取不存在的对象抛业务异常而不是返回 null")
    void getMissingObjectThrows() {
        assertThatThrownBy(() -> storage.get("objects/not-exist"))
                .isInstanceOf(BusinessException.class);
    }
}
