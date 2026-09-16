package com.cloudstorage.service.quota;

import com.cloudstorage.service.limiter.lock.DistributedLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 配额对账任务。用分布式锁做"单飞"：多实例部署时同一时刻只有一个节点在对账，
 * 避免并发重算互相覆盖；锁 TTL 短于任务周期，任务异常退出也能自然释放。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuotaReconcileJob {

    private static final String LOCK_KEY = "cs:lock:quota:reconcile";

    private final QuotaService quotaService;
    private final DistributedLock distributedLock;

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT30M")
    public void reconcile() {
        Optional<Integer> reconciled =
                distributedLock.execute(LOCK_KEY, Duration.ofMinutes(5), quotaService::reconcile);
        if (reconciled.isPresent()) {
            log.info("配额对账任务完成，处理用户数={}", reconciled.get());
        } else {
            log.debug("配额对账任务被其它实例持有，本次跳过");
        }
    }
}
