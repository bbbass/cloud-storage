package com.cloudstorage.service.storage.impl;

import com.cloudstorage.common.exception.BusinessException;
import com.cloudstorage.common.result.ErrorCode;
import com.cloudstorage.service.storage.StorageProperties;
import com.cloudstorage.service.storage.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;

/**
 * 本地磁盘实现，默认生效（app.storage.type=local 或未配置）。
 * 仅用于开发与降级演示：单机、无副本、无生命周期策略。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalStorageService implements StorageService {

    private final Path root;

    public LocalStorageService(StorageProperties properties) throws IOException {
        this.root = Paths.get(properties.getLocalPath()).toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        log.info("LocalStorageService 已启用，存储根目录：{}", this.root);
    }

    @Override
    public String type() {
        return "local";
    }

    @Override
    public void put(String objectKey, InputStream in, long size) {
        put(objectKey, in, size, "application/octet-stream");
    }

    @Override
    public void put(String objectKey, InputStream in, long size, String contentType) {
        Path target = resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            moveQuietly(tmp, target);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.STORAGE_ERROR, "写入对象失败：" + objectKey);
        }
    }

    @Override
    public void compose(String targetKey, List<String> sourceKeys, String contentType) {
        Path target = resolve(targetKey);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                for (String sourceKey : sourceKeys) {
                    Path source = resolve(sourceKey);
                    if (!Files.isRegularFile(source)) {
                        throw new BusinessException(ErrorCode.UPLOAD_PART_MISSING, "缺少分片对象：" + sourceKey);
                    }
                    Files.copy(source, out);
                }
            }
            moveQuietly(tmp, target);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.STORAGE_ERROR, "合并对象失败：" + targetKey);
        }
    }

    @Override
    public InputStream get(String objectKey) {
        Path target = resolve(objectKey);
        if (!Files.isRegularFile(target)) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        try {
            return Files.newInputStream(target);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.STORAGE_ERROR, "读取对象失败：" + objectKey);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(resolve(objectKey));
        } catch (IOException e) {
            log.warn("删除对象失败：{}", objectKey, e);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        return Files.isRegularFile(resolve(objectKey));
    }

    @Override
    public long size(String objectKey) {
        Path target = resolve(objectKey);
        if (!Files.isRegularFile(target)) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        try {
            return Files.size(target);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.STORAGE_ERROR, "读取对象大小失败：" + objectKey);
        }
    }

    @Override
    public String signedUrl(String objectKey, Duration ttl) {
        // 本地实现没有签名能力，交给 web 层的下载接口做鉴权。
        return "/api/v1/files/download?key=" + objectKey;
    }

    /** 优先原子移动，Windows 上文件系统不支持时退化为普通覆盖移动。 */
    private void moveQuietly(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 解析并校验路径，防止 ../ 越权访问到存储根目录之外。 */
    private Path resolve(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "objectKey 不能为空");
        }
        Path target = root.resolve(objectKey.replace('\\', '/')).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "非法的 objectKey：" + objectKey);
        }
        return target;
    }
}
