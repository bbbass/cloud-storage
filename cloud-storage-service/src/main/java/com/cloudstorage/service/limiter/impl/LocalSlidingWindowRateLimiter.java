package com.cloudstorage.service.limiter.impl;

import com.cloudstorage.service.limiter.RateLimitResult;
import com.cloudstorage.service.limiter.RateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 单机滑动窗口兜底（app.redis.enabled=false 时生效）。
 * 语义与 Redis 版一致，但只在单实例内有效 —— 多实例部署必须切回 Redis 实现。
 */
@Service
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "false", matchIfMissing = true)
public class LocalSlidingWindowRateLimiter implements RateLimiter {

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    @Override
    public RateLimitResult acquire(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        long windowMs = window.toMillis();
        Deque<Long> deque = windows.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && now - deque.peekFirst() >= windowMs) {
                deque.pollFirst();
            }
            if (deque.size() >= limit) {
                long retryAfterMs = windowMs - (now - deque.peekFirst());
                return RateLimitResult.builder()
                        .allowed(false)
                        .current(deque.size())
                        .limit(limit)
                        .retryAfterMs(Math.max(retryAfterMs, 0))
                        .build();
            }
            deque.addLast(now);
            return RateLimitResult.builder()
                    .allowed(true)
                    .current(deque.size())
                    .limit(limit)
                    .retryAfterMs(0)
                    .build();
        }
    }

    @Override
    public String type() {
        return "local-sliding-window";
    }

    /** 清掉空窗口，避免 key 无限增长 */
    @Scheduled(fixedDelayString = "PT5M")
    void evictEmptyWindows() {
        windows.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }
}
