package com.cloudstorage.service.storage.impl;

import com.cloudstorage.common.exception.BusinessException;
import com.cloudstorage.common.result.ErrorCode;
import com.cloudstorage.service.storage.StorageProperties;
import com.cloudstorage.service.storage.StorageService;
import io.minio.BucketExistsArgs;
import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/**
 * MinIO 实现（storage.type=minio，开发与演示默认走这条真实链路）。
 * 合并用服务端 compose，不在应用侧拼接 —— 这是"分片上传"里最容易被追问的性能点。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "minio")
public class MinioStorageService implements StorageService {

    private final MinioClient client;
    private final String bucket;

    public MinioStorageService(StorageProperties properties) {
        StorageProperties.Minio minio = properties.getMinio();
        this.bucket = minio.getBucket();
        if (!StringUtils.hasText(minio.getAccessKey()) || !StringUtils.hasText(minio.getSecretKey())) {
            // 凭据只来自配置/环境变量，仓库里不留默认值；这里给出可执行的错误提示而不是让 SDK 抛参数异常
            throw new BusinessException(ErrorCode.STORAGE_ERROR,
                    "MinIO 凭据未配置：请设置 MINIO_ACCESS_KEY / MINIO_SECRET_KEY 环境变量，"
                            + "或按 application-dev.yml.example 创建 application-dev.yml");
        }
        this.client = MinioClient.builder()
                .endpoint(minio.getEndpoint())
                .credentials(minio.getAccessKey(), minio.getSecretKey())
                .build();
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO bucket 不存在，已自动创建：{}", bucket);
            }
            log.info("MinioStorageService 已启用，endpoint={}，bucket={}", minio.getEndpoint(), bucket);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.STORAGE_ERROR, "MinIO 初始化失败：" + e.getMessage());
        }
    }

    @Override
    public String type() {
        return "minio";
    }

    @Override
    public void put(String objectKey, InputStream in, long size) {
        put(objectKey, in, size, "application/octet-stream");
    }

    @Override
    public void put(String objectKey, InputStream in, long size, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(in, size, -1)
                    .contentType(contentType == null ? "application/octet-stream" : contentType)
                    .build());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.STORAGE_ERROR, "写入对象失败：" + objectKey + "，" + e.getMessage());
        }
    }

    @Override
    public void compose(String targetKey, List<String> sourceKeys, String contentType) {
        if (sourceKeys == null || sourceKeys.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "compose 源对象不能为空");
        }
        List<ComposeSource> sources = sourceKeys.stream()
                .map(key -> ComposeSource.builder().bucket(bucket).object(key).build())
                .toList();
        try {
            client.composeObject(ComposeObjectArgs.builder()
                    .bucket(bucket)
                    .object(targetKey)
                    .sources(sources)
                    .build());
            log.info("compose 完成：{} 个分片 → {}", sources.size(), targetKey);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.STORAGE_ERROR, "合并对象失败：" + targetKey + "，" + e.getMessage());
        }
    }

    @Override
    public InputStream get(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND, "读取对象失败：" + objectKey);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            log.warn("删除对象失败：{}，{}", objectKey, e.getMessage());
        }
    }

    @Override
    public boolean exists(String objectKey) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return true;
        } catch (ErrorResponseException e) {
            return false;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.STORAGE_ERROR, "查询对象失败：" + objectKey);
        }
    }

    @Override
    public long size(String objectKey) {
        try {
            StatObjectResponse stat = client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return stat.size();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND, "对象不存在：" + objectKey);
        }
    }

    @Override
    public String signedUrl(String objectKey, Duration ttl) {
        try {
            int seconds = (int) Math.max(1, Math.min(ttl.getSeconds(), 7 * 24 * 3600));
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(seconds)
                    .build());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.STORAGE_ERROR, "生成签名 URL 失败：" + objectKey);
        }
    }
}
