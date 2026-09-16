package com.cloudstorage.service.quota;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 配额配置：app.quota.*
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.quota")
public class QuotaProperties {

    /** 每个用户的默认配额：1 GiB */
    private long defaultQuotaBytes = 1024L * 1024 * 1024;

    /** Redis key 前缀，与其它项目隔离 */
    private String keyPrefix = "cs:quota";
}
