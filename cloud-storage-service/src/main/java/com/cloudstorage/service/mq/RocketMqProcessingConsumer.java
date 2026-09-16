package com.cloudstorage.service.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RocketMQ 消费者（自研封装，非 starter）。
 *
 * 关键取舍：**永远返回 CONSUME_SUCCESS**，重试与死信完全由 DB 状态机控制。
 * 否则会出现"MQ 自己重投 + 我方 DB 重试"两套机制叠加，重试次数失控、也说不清死信到底在哪。
 * 消息丢失由补偿任务（扫描 PENDING）兜底，所以这里 ack 掉是安全的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mq.enabled", havingValue = "true")
public class RocketMqProcessingConsumer {

    static {
        // RocketMQ 客户端默认往 ${user.home}/logs 写文件日志，本机 user.home 是 C:\ 会抛 FileNotFoundException
        System.setProperty("rocketmq.client.logUseSlf4j", "true");
    }

    private final MqProperties mqProperties;
    private final ProcessingTaskDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    private DefaultMQPushConsumer consumer;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        try {
            DefaultMQPushConsumer pushConsumer = new DefaultMQPushConsumer(mqProperties.getConsumerGroup());
            pushConsumer.setNamesrvAddr(mqProperties.getNameServer());
            pushConsumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
            pushConsumer.setConsumeThreadMin(mqProperties.getConsumeThreadMin());
            pushConsumer.setConsumeThreadMax(mqProperties.getConsumeThreadMax());
            pushConsumer.subscribe(mqProperties.getTopic(), mqProperties.getTag());
            pushConsumer.registerMessageListener((MessageListenerConcurrently) this::onMessages);
            pushConsumer.start();
            this.consumer = pushConsumer;
            log.info("RocketMQ 消费者已启动：group={}, topic={}, threads={}-{}",
                    mqProperties.getConsumerGroup(), mqProperties.getTopic(),
                    mqProperties.getConsumeThreadMin(), mqProperties.getConsumeThreadMax());
        } catch (Exception e) {
            log.error("RocketMQ 消费者启动失败：{}", e.getMessage());
        }
    }

    private ConsumeConcurrentlyStatus onMessages(List<MessageExt> messages, Object context) {
        for (MessageExt message : messages) {
            try {
                ProcessingMessage payload = objectMapper.readValue(message.getBody(), ProcessingMessage.class);
                dispatcher.dispatch(payload.getTaskId());
            } catch (Exception e) {
                log.error("消息解析/执行异常（任务仍在 DB 中，由补偿任务兜底）：msgId={}", message.getMsgId(), e);
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    @PreDestroy
    public void shutdown() {
        if (consumer != null) {
            consumer.shutdown();
            log.info("RocketMQ 消费者已关闭");
        }
    }
}
