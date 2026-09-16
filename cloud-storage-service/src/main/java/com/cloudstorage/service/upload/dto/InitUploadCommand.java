package com.cloudstorage.service.upload.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 初始化上传的入参（客户端先算好整文件 sha256，服务端据此做秒传与校验）。
 */
@Data
@Builder
public class InitUploadCommand {

    private String fileName;
    private Long fileSize;
    /** 整文件 sha256（十六进制小写） */
    private String sha256;
    /** 可为空，缺省用服务端默认分片大小 */
    private Long chunkSize;
    private String contentType;
    /** 目标目录，0 = 根目录 */
    private Long parentId;
}
