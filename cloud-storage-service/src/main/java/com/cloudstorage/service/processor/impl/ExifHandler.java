package com.cloudstorage.service.processor.impl;

import com.cloudstorage.common.exception.BusinessException;
import com.cloudstorage.common.result.ErrorCode;
import com.cloudstorage.service.processor.ProcessingContext;
import com.cloudstorage.service.processor.ProcessingHandler;
import com.cloudstorage.service.processor.TaskTypes;
import com.cloudstorage.service.storage.StorageService;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EXIF 处理器：抽取相机型号、拍摄时间、GPS 等元数据，序列化成 JSON 存入任务结果。
 * 没有 EXIF、或格式不被识别（如 WebP）都记为 NONE —— 那是正常情况，不该打成死信。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExifHandler implements ProcessingHandler {

    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    @Override
    public String taskType() {
        return TaskTypes.EXIF;
    }

    @Override
    public void handle(ProcessingContext context) {
        if (!context.isImage()) {
            context.setResult("SKIPPED:not-image");
            return;
        }
        try (InputStream in = storageService.get(context.getObjectKey())) {
            Map<String, Object> exif = parse(in);
            context.setResult(exif.isEmpty() ? "NONE" : objectMapper.writeValueAsString(exif));
            log.info("EXIF 解析完成：fileId={}, fields={}", context.getFileId(), exif.keySet());
        } catch (ImageProcessingException e) {
            log.warn("不支持的图片格式，跳过 EXIF：fileId={}, {}", context.getFileId(), e.getMessage());
            context.setResult("NONE:unsupported-format");
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.STORAGE_ERROR, "读取图片失败：" + e.getMessage());
        }
    }

    /** 单独成方法便于单测：直接喂字节流验证解析逻辑 */
    public Map<String, Object> parse(InputStream in) throws ImageProcessingException, IOException {
        Metadata metadata = ImageMetadataReader.readMetadata(in);
        Map<String, Object> result = new LinkedHashMap<>();

        ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        if (ifd0 != null) {
            putIfPresent(result, "make", ifd0.getDescription(ExifIFD0Directory.TAG_MAKE));
            putIfPresent(result, "model", ifd0.getDescription(ExifIFD0Directory.TAG_MODEL));
            putIfPresent(result, "orientation", ifd0.getDescription(ExifIFD0Directory.TAG_ORIENTATION));
        }
        ExifSubIFDDirectory subIfd = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        if (subIfd != null) {
            putIfPresent(result, "dateTimeOriginal",
                    subIfd.getDescription(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL));
            putIfPresent(result, "exposureTime", subIfd.getDescription(ExifSubIFDDirectory.TAG_EXPOSURE_TIME));
            putIfPresent(result, "fNumber", subIfd.getDescription(ExifSubIFDDirectory.TAG_FNUMBER));
            putIfPresent(result, "iso", subIfd.getDescription(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT));
        }
        GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);
        if (gps != null) {
            GeoLocation location = gps.getGeoLocation();
            if (location != null && !location.isZero()) {
                result.put("latitude", location.getLatitude());
                result.put("longitude", location.getLongitude());
            }
        }
        return result;
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
