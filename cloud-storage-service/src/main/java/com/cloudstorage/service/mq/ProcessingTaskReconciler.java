package com.cloudstorage.service.mq;

import com.cloudstorage.domain.entity.ProcessingTask;
import com.cloudstorage.domain.mapper.ProcessingTaskMapper;
import com.cloudstorage.service.limiter.lock.DistributedLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 任务补偿：这是"本地消息表"模式的兜底一环。
 * 1) 捞 PENDING 且到点的任务重新投递（消息发失败、broker 抖动、消费者挂掉都能自愈）；
 * 2) 捞长时间卡在 RUNNING 的任务（进程崩溃遗留）重置回 PENDING。
 * 多实例下用分布式锁保证同一时刻只有一个节点在补偿。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessingTaskReconciler {

    private static final String LOCK_KEY = "cs:lock:mq:reconcile";
    private static final int BATCH_SIZE = 200;

    private final ProcessingTaskMapper taskMapper;
    private final MessagePublisher messagePublisher;
    private final MqProperties mqProperties;
    private final DistributedLock distributedLock;

    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "${app.mq.reconcile-interval:PT30S}")
    public void reconcile() {
        Optional<Integer> handled = distributedLock.execute(LOCK_KEY, Duration.ofMinutes(2), () -> {
            int requeued = requeuePending();
            int recovered = recoverStuck();
            return requeued + recovered;
        });
        handled.filter(count -> count > 0)
                .ifPresent(count -> log.info("任务补偿完成：重投/恢复 {} 个任务", count));
    }

    private int requeuePending() {
        List<ProcessingTask> tasks = taskMapper.selectDispatchable(BATCH_SIZE);
        for (ProcessingTask task : tasks) {
            messagePublisher.publish(ProcessingMessage.of(task), 0);
        }
        return tasks.size();
    }

    private int recoverStuck() {
        LocalDateTime before = LocalDateTime.now().minus(mqProperties.getStuckRunningTimeout());
        List<ProcessingTask> stuck = taskMapper.selectStuckRunning(before, BATCH_SIZE);
        for (ProcessingTask task : stuck) {
            taskMapper.resetStuckRunning(task.getId());
            messagePublisher.publish(ProcessingMessage.of(task), 0);
            log.warn("发现卡在 RUNNING 的任务，已重置重投：taskId={}", task.getId());
        }
        return stuck.size();
    }
}
