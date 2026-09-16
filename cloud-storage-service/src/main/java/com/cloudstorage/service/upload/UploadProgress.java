package com.cloudstorage.service.upload;

import lombok.Builder;
import lombok.Data;

/**
 * 上传进度事件（SSE 推送用）。service 层只负责产生事件，不依赖 Spring MVC。
 */
@Data
@Builder
public class UploadProgress {

    private Long uploadId;
    private String status;
    private Integer uploadedParts;
    private Integer totalParts;
    private Integer percent;
    private String message;
    private long timestamp;
}
