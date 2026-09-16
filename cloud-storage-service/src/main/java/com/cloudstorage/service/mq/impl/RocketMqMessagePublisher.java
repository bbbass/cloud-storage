package com.cloudstorage.service.mq.impl;

import com.cloudstorage.service.mq.MessagePublisher;
import com.cloudstorage.service.mq.MqProperties;
import com.cloudstorage.service.mq.ProcessingMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * RocketMQ 生产者。用 rocketmq-client 直接封装，不用 rocketmq-spring-boot-starter
 * （2.2.x 只适配 Spring Boot 2.x，在 Boot 3.5 下不会自动装配，见 AGENTS.md 7.7）。
 *
 * 重试用 RocketMQ 的延迟等级做退避：1s / 5s / 10s / 30s / 1m，避免失败任务立刻重试打爆下游。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mq.enabled", havingValue = "true")
public class RocketMqMessagePublisher implements MessagePublisher {

    static {
        System.setProperty("rocketmq.client.logUseSlf4j", "true");
    }

    private static final int[] DELAY_LEVELS = {1, 2, 3, 4, 5};

    private final MqProperties mqProperties;
    private final ObjectMapper objectMapper;

    private DefaultMQProducer producer;
    private volatile boolean available;

    @Override
    public void publish(ProcessingMessage message, int retryCount) {
        if (!ensureStarted()) {
            // 投递不出去不是致命错误：任务在 DB 里仍是 PENDING，补偿任务会扫出来重投
            log.error("RocketMQ 不可用，任务保持 PENDING 等待补偿：taskId={}", message.getTaskId());
            return;
        }
        try {
            byte[] body = objectMapper.writeValueAsBytes(message);
            Message mqMessage = new Message(mqProperties.getTopic(), mqProperties.getTag(),
                    message.messageKey(), body);
            if (retryCount > 0) {
                mqMessage.setDelayTimeLevel(DELAY_LEVELS[Math.min(retryCount - 1, DELAY_LEVELS.length - 1)]);
            }
            SendResult sendResult = producer.send(mqMessage);
            log.debug("任务消息已投递：taskId={}, retryCount={}, msgId={}",
                    message.getTaskId(), retryCount, sendResult.getMsgId());
        } catch (Exception e) {
            log.error("任务消息投递失败（等补偿任务重投）：taskId={}, error={}", message.getTaskId(), e.getMessage());
        }
    }

    @Override
    public String type() {
        return "rocketmq";
    }

    /** 懒启动：broker 暂时不可用也不应该让应用起不来 */
    private synchronized boolean ensureStarted() {
        if (available) {
            return true;
        }
        try {
            DefaultMQProducer newProducer = new DefaultMQProducer(mqProperties.getProducerGroup());
            newProducer.setNamesrvAddr(mqProperties.getNameServer());
            newProducer.setSendMsgTimeout(5000);
            newProducer.setRetryTimesWhenSendFailed(2);
            newProducer.start();
            this.producer = newProducer;
            this.available = true;
            log.info("RocketMQ 生产者已启动：namesrv={}, topic={}",
                    mqProperties.getNameServer(), mqProperties.getTopic());
            return true;
        } catch (Exception e) {
            log.error("RocketMQ 生产者启动失败：{}", e.getMessage());
            return false;
        }
    }

    @PreDestroy
    public void shutdown() {
        if (producer != null) {
            producer.shutdown();
            available = false;
            log.info("RocketMQ 生产者已关闭");
        }
    }

    /** 供自检接口使用 */
    public boolean isAvailable() {
        return available;
    }

}
