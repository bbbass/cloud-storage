package com.cloudstorage.service.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对象存储配置：app.storage.*（见 AGENTS.md 7.5，默认走真实 MinIO）
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    /** minio / local */
    private String type = "minio";

    /** 本地实现（storage.type=local）的根目录 */
    private String localPath = "./data/storage";

    private Minio minio = new Minio();

    @Data
    public static class Minio {
        private String endpoint = "http://localhost:9000";
        /**
         * 凭据不写死在代码里：来自 application-dev.yml（已 gitignore，模板见 application-dev.yml.example）
         * 或环境变量 MINIO_ACCESS_KEY / MINIO_SECRET_KEY。
         */
        private String accessKey = "";
        private String secretKey = "";
        private String bucket = "cloud-storage";
    }
}
