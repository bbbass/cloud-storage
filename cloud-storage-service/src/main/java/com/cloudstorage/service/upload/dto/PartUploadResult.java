package com.cloudstorage.service.upload.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PartUploadResult {

    private Long uploadId;
    private Integer partNo;
    private Long partSize;
    private String sha256;
    private Integer uploadedParts;
    private Integer totalParts;
    private Integer percent;
}
