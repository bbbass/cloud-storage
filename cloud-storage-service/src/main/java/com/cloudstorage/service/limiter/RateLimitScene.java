package com.cloudstorage.service.limiter;

import java.time.Duration;

/**
 * 限流场景与默认阈值，可用 app.limiter.scenes.<kebab-name>.* 覆盖。
 * 说明：分片上传按"次数"限制（滑动窗口），不按字节——带宽级限速属于网关/传输层，
 * 这里解决的是"单设备并发占满连接与后端资源"的问题。
 */
public enum RateLimitScene {

    UPLOAD_INIT("上传初始化", 60, 20, Duration.ofMinutes(1)),
    UPLOAD_PART("分片上传", 1200, 300, Duration.ofMinutes(1)),
    UPLOAD_COMPLETE("合并上传", 60, 20, Duration.ofMinutes(1)),
    FILE_META("文件元数据查询", 1200, 300, Duration.ofMinutes(1)),
    DOWNLOAD("文件下载", 300, 100, Duration.ofMinutes(1));

    private final String label;
    private final int userLimit;
    private final int deviceLimit;
    private final Duration window;

    RateLimitScene(String label, int userLimit, int deviceLimit, Duration window) {
        this.label = label;
        this.userLimit = userLimit;
        this.deviceLimit = deviceLimit;
        this.window = window;
    }

    public String label() {
        return label;
    }

    public int userLimit() {
        return userLimit;
    }

    public int deviceLimit() {
        return deviceLimit;
    }

    public Duration window() {
        return window;
    }

    /** 供配置覆盖用的 kebab 名称，如 UPLOAD_PART -> upload-part */
    public String key() {
        return name().toLowerCase().replace('_', '-');
    }
}
