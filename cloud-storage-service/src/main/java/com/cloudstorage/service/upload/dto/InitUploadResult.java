package com.cloudstorage.service.upload.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 初始化上传的返回。
 * instantUpload=true 表示秒传成功（无需再传分片，fileId 已生成）。
 */
@Data
@Builder
public class InitUploadResult {

    private boolean instantUpload;
    private Long uploadId;
    private Long fileId;
    private Long objectId;
    private Integer chunkSize;
    private Integer chunkTotal;
    /** 已存在的分片序号，客户端只需补传缺失的（断点续传） */
    private List<Integer> uploadedParts;
    private LocalDateTime expireTime;
    private String message;
}
