package com.cloudstorage.service.upload.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CompleteUploadResult {

    private Long uploadId;
    private Long fileId;
    private Long objectId;
    private String sha256;
    private Long size;
    /** true 表示物理对象是复用已存在的（去重命中） */
    private boolean deduped;
    private long costMs;
}
