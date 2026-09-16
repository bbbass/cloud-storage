package com.cloudstorage.service.mq.impl;

import com.cloudstorage.service.mq.MessagePublisher;
import com.cloudstorage.service.mq.MqProperties;
import com.cloudstorage.service.mq.ProcessingMessage;
import com.cloudstorage.service.mq.ProcessingTaskDispatcher;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 单机兜底：用进程内调度线程池模拟"投递 + 延迟重试"。
 * 只在 app.mq.enabled=false 时生效，用于没起 broker 时调试主流程；多实例下无削峰与死信语义。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.mq.enabled", havingValue = "false", matchIfMissing = true)
public class LocalMessagePublisher implements MessagePublisher {

    private static final long[] DELAY_SECONDS = {1, 5, 10, 30, 60};

    private final MqProperties mqProperties;
    private final ProcessingTaskDispatcher dispatcher;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "local-mq-dispatcher");
        thread.setDaemon(true);
        return thread;
    });

    public LocalMessagePublisher(MqProperties mqProperties, ProcessingTaskDispatcher dispatcher) {
        this.mqProperties = mqProperties;
        this.dispatcher = dispatcher;
        log.warn("app.mq.enabled=false，使用进程内消息兜底（无削峰/无死信持久化语义）");
    }

    @Override
    public void publish(ProcessingMessage message, int retryCount) {
        long delay = retryCount > 0 ? DELAY_SECONDS[Math.min(retryCount - 1, DELAY_SECONDS.length - 1)] : 0;
        executor.schedule(() -> dispatcher.dispatch(message.getTaskId()), delay, TimeUnit.SECONDS);
    }

    @Override
    public String type() {
        return "local-executor";
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
