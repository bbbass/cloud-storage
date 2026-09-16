package com.cloudstorage.service.mq;

import com.cloudstorage.common.exception.BusinessException;
import com.cloudstorage.common.result.ErrorCode;
import com.cloudstorage.domain.entity.FileItem;
import com.cloudstorage.domain.entity.FileObject;
import com.cloudstorage.domain.entity.ProcessingTask;
import com.cloudstorage.domain.mapper.FileItemMapper;
import com.cloudstorage.domain.mapper.FileObjectMapper;
import com.cloudstorage.domain.mapper.ProcessingTaskMapper;
import com.cloudstorage.service.processor.ProcessingContext;
import com.cloudstorage.service.processor.ProcessingHandler;
import com.cloudstorage.service.processor.ProcessingHandlerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 任务执行器：与传输层无关，MQ 消费者和本地兜底都调它。
 *
 * 幂等保证：dispatch 前先做 PENDING → RUNNING 的 CAS，抢不到就直接返回。
 * 这意味着"消息重复投递 / 补偿任务重复扫描 / 消费者重投"都不会重复执行。
 *
 * 重试与死信：失败时把 retry_count 加一，用 RocketMQ 延迟等级做退避重投；
 * 超过 maxRetry 置为 DEAD（死信），由重放接口人工恢复。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessingTaskDispatcher {

    private final ProcessingTaskMapper taskMapper;
    private final FileItemMapper fileItemMapper;
    private final FileObjectMapper fileObjectMapper;
    private final ProcessingHandlerRegistry handlerRegistry;
    private final MqProperties mqProperties;
    /** 用 ObjectProvider 打破"发布者依赖执行器、执行器又要发布重试消息"的循环依赖 */
    private final ObjectProvider<MessagePublisher> messagePublisherProvider;

    public void dispatch(Long taskId) {
        ProcessingTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("任务不存在，丢弃消息：taskId={}", taskId);
            return;
        }
        if (taskMapper.casMarkRunning(taskId) != 1) {
            log.debug("任务已在执行或已完成，跳过（幂等）：taskId={}, status={}", taskId, task.getStatus());
            return;
        }

        long start = System.currentTimeMillis();
        try {
            ProcessingContext context = buildContext(task);
            ProcessingHandler handler = handlerRegistry.get(task.getTaskType());
            handler.handle(context);
            taskMapper.markDone(taskId, context.getResult(), System.currentTimeMillis() - start);
            log.info("任务完成：taskId={}, type={}, fileId={}, cost={}ms",
                    taskId, task.getTaskType(), task.getFileId(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            handleFailure(task, e);
        }
    }

    private void handleFailure(ProcessingTask task, Exception e) {
        String error = truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        int nextAttempt = retryCount + 1;
        if (nextAttempt > mqProperties.getMaxRetry()) {
            taskMapper.markDead(task.getId(), error);
            log.error("任务重试耗尽，进入死信：taskId={}, type={}, retry={}/{}, error={}",
                    task.getId(), task.getTaskType(), retryCount, mqProperties.getMaxRetry(), error);
            return;
        }
        Duration backoff = mqProperties.getRetryBackoff();
        taskMapper.markRetry(task.getId(), LocalDateTime.now().plus(backoff), error);
        messagePublisherProvider.getObject().publish(ProcessingMessage.of(task), nextAttempt);
        log.warn("任务失败，稍后重试（第 {}/{} 次）：taskId={}, error={}",
                nextAttempt, mqProperties.getMaxRetry(), task.getId(), error);
    }

    private ProcessingContext buildContext(ProcessingTask task) {
        FileItem item = fileItemMapper.selectById(task.getFileId());
        FileObject object = fileObjectMapper.selectById(task.getObjectId());
        if (item == null || object == null) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND, "任务关联的文件或对象不存在");
        }
        return ProcessingContext.builder()
                .taskId(task.getId())
                .fileId(item.getId())
                .userId(item.getUserId())
                .fileName(item.getFileName())
                .contentType(object.getContentType())
                .objectKey(object.getObjectKey())
                .size(object.getSize())
                .build();
    }

    private String truncate(String message) {
        return message.length() <= 480 ? message : message.substring(0, 480) + "...";
    }
}
