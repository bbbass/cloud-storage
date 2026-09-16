package com.cloudstorage.service.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/**
 * 对象存储抽象。默认实现是本地磁盘，MinIO 就绪后按 app.storage.type 切换，接口不变。
 */
public interface StorageService {

    /** 实现类型标识：local / minio。 */
    String type();

    /** 写入对象；size &lt; 0 表示未知长度。 */
    void put(String objectKey, InputStream in, long size);

    /** 写入对象并指定 content-type。 */
    void put(String objectKey, InputStream in, long size, String contentType);

    /**
     * 服务端合并：把多个源对象拼接成目标对象，不经过应用内存/带宽。
     * 分片上传的"合并"必须走它 —— S3/MinIO 的 compose 要求除最后一片外每片 ≥ 5MiB。
     */
    void compose(String targetKey, List<String> sourceKeys, String contentType);

    /** 读取对象流；对象不存在时抛 BusinessException(FILE_NOT_FOUND)。 */
    InputStream get(String objectKey);

    void delete(String objectKey);

    boolean exists(String objectKey);

    long size(String objectKey);

    /** 生成带时效的访问地址；本地实现返回受控下载接口路径。 */
    String signedUrl(String objectKey, Duration ttl);
}
