package com.cloudstorage.service.processor;

import lombok.Builder;
import lombok.Data;

/**
 * 处理器上下文：任务信息 + 文件元数据。处理器只依赖它，不直接碰 Mapper。
 */
@Data
@Builder
public class ProcessingContext {

    private Long taskId;
    private Long fileId;
    private Long userId;
    private String fileName;
    private String contentType;
    private String objectKey;
    private Long size;
    /** 处理结果（缩略图 objectKey、EXIF JSON 等），由处理器写入 */
    private String result;

    public boolean isImage() {
        return contentType != null && contentType.startsWith("image/");
    }
}
