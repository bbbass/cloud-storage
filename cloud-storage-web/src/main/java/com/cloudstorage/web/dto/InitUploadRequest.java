package com.cloudstorage.web.dto;

import lombok.Data;

/**
 * 初始化上传请求体（web 层 DTO，只做参数承载，不掺业务逻辑）。
 */
@Data
public class InitUploadRequest {

    private String fileName;
    private Long fileSize;
    /** 整文件 sha256，秒传与合并后校验都用它 */
    private String sha256;
    private Long chunkSize;
    private String contentType;
    private Long parentId;
}
