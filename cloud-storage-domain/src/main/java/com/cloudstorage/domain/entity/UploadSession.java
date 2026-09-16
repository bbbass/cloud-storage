package com.cloudstorage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 分片上传会话。id 同时是对外暴露的 uploadId。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("upload_sessions")
public class UploadSession extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String deviceId;
    private String fileName;
    private Long fileSize;
    private Integer chunkSize;
    private Integer chunkTotal;
    private String sha256;
    private String contentType;
    private Long parentId;
    private String status;
    private Long objectId;
    private Long fileId;
    private LocalDateTime expireTime;
}
