package com.cloudstorage.service.mq;

/**
 * 消息投递抽象。实现有两种：
 * - RocketMqMessagePublisher：真实 RocketMQ（app.mq.enabled=true）
 * - LocalMessagePublisher：进程内线程池（app.mq.enabled=false 的单机兜底）
 */
public interface MessagePublisher {

    /**
     * 投递任务消息。
     * @param retryCount 0 = 立即投递；&gt;0 时用延迟等级做退避重试
     */
    void publish(ProcessingMessage message, int retryCount);

    String type();
}
