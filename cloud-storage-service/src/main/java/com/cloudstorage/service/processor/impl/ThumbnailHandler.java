package com.cloudstorage.service.processor.impl;

import com.cloudstorage.common.exception.BusinessException;
import com.cloudstorage.common.result.ErrorCode;
import com.cloudstorage.service.processor.ProcessingContext;
import com.cloudstorage.service.processor.ProcessingHandler;
import com.cloudstorage.service.processor.TaskTypes;
import com.cloudstorage.service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 缩略图处理器：从对象存储读原图 → Thumbnailator 缩放 → 写回缩略图对象。
 * 处理失败抛异常，由调度器按退避策略重试，重试耗尽进死信、可重放。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ThumbnailHandler implements ProcessingHandler {

    private static final int THUMBNAIL_SIZE = 256;

    private final StorageService storageService;

    @Override
    public String taskType() {
        return TaskTypes.THUMBNAIL;
    }

    @Override
    public void handle(ProcessingContext context) {
        if (!context.isImage()) {
            context.setResult("SKIPPED:not-image");
            return;
        }
        Path tmp = null;
        try (InputStream in = storageService.get(context.getObjectKey())) {
            tmp = Files.createTempFile("cs-thumb-", ".jpg");
            Thumbnails.of(in)
                    .size(THUMBNAIL_SIZE, THUMBNAIL_SIZE)
                    .keepAspectRatio(true)
                    .outputFormat("jpg")
                    .toFile(tmp.toFile());
            long thumbnailSize = Files.size(tmp);
            String key = thumbnailKey(context.getObjectKey());
            try (InputStream thumbnailIn = Files.newInputStream(tmp)) {
                storageService.put(key, thumbnailIn, thumbnailSize, "image/jpeg");
            }
            context.setResult("{\"objectKey\":\"" + key + "\",\"size\":" + thumbnailSize + "}");
            log.info("缩略图生成完成：fileId={}, key={}, size={}B", context.getFileId(), key, thumbnailSize);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.STORAGE_ERROR, "生成缩略图失败：" + e.getMessage());
        } finally {
            deleteQuietly(tmp);
        }
    }

    /** objects/ab/&lt;sha&gt; → thumbnails/ab/&lt;sha&gt;_256.jpg */
    private String thumbnailKey(String objectKey) {
        int index = objectKey.indexOf('/');
        String suffix = index >= 0 ? objectKey.substring(index + 1) : objectKey;
        return "thumbnails/" + suffix + "_" + THUMBNAIL_SIZE + ".jpg";
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除临时缩略图失败：{}", path, e);
        }
    }
}
