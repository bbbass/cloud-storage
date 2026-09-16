package com.cloudstorage.service.upload;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 上传相关配置：app.upload.*
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.upload")
public class UploadProperties {

    /** 客户端未指定分片大小时使用 */
    private long defaultChunkSize = 5L * 1024 * 1024;

    /**
     * 最小分片大小。默认 5MiB 不是随手写的：MinIO/S3 的服务端 compose 要求
     * 除最后一片外每片 ≥ 5MiB，小于这个值合并会失败。
     */
    private long minChunkSize = 5L * 1024 * 1024;

    private long maxChunkSize = 64L * 1024 * 1024;

    /** 分片数量上限（S3 compose 单次最多 10000 个源对象） */
    private int maxChunkTotal = 10000;

    /** 合并后是否回读校验整文件 sha256（默认开，1GB 约 1-2 秒） */
    private boolean verifyAfterMerge = true;

    /** 上传会话有效期 */
    private Duration sessionTtl = Duration.ofHours(24);
}
