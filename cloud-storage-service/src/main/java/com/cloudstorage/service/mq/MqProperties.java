package com.cloudstorage.service.mq;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 消息与异步任务配置：app.mq.*
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.mq")
public class MqProperties {

    /** false 时用进程内线程池兜底（单机调试），生产必须为 true */
    private boolean enabled = true;

    private String nameServer = "localhost:9876";
    private String producerGroup = "cloud-storage-producer";
    private String consumerGroup = "cloud-storage-consumer";
    private String topic = "cloud-storage-processing";
    private String tag = "processing";

    /** 失败重试次数（不含首次执行），超过即进入死信 DEAD */
    private int maxRetry = 3;

    /** 首次重试退避，之后按 1s/5s/10s/30s/1m 的 RocketMQ 延迟等级放大 */
    private Duration retryBackoff = Duration.ofSeconds(5);

    private int consumeThreadMin = 2;
    private int consumeThreadMax = 8;

    /** 补偿任务轮询间隔：捞回"没投递出去 / 投递丢失 / 卡在 RUNNING"的任务 */
    private Duration reconcileInterval = Duration.ofSeconds(30);

    /** RUNNING 超过这个时长判定为进程崩溃遗留 */
    private Duration stuckRunningTimeout = Duration.ofMinutes(5);
}
