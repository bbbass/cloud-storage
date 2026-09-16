package com.cloudstorage.service.upload.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class UploadStatusView {

    private Long uploadId;
    private String status;
    private String fileName;
    private Long fileSize;
    private Integer chunkSize;
    private Integer totalParts;
    private List<Integer> uploadedParts;
    private Integer percent;
    private Long fileId;
    private Long objectId;
    private LocalDateTime expireTime;
}
