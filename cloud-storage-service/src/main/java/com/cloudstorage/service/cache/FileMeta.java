package com.cloudstorage.service.cache;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件元数据视图（缓存对象）。故意只放读多写少、变更不频繁的字段。
 */
@Data
@Builder
public class FileMeta {

    private Long fileId;
    private Long userId;
    private Long objectId;
    private String fileName;
    private Long size;
    private String sha256;
    private String contentType;
    private String objectKey;
    private LocalDateTime createTime;
}
