package com.cloudstorage.service.limiter;

import java.time.Duration;

/**
 * 限流算法抽象。key 由调用方按 `prefix:scene:dimension:subject` 组装，
 * 实现只负责"在 window 内最多放行 limit 次"这一件事。
 */
public interface RateLimiter {

    RateLimitResult acquire(String key, int limit, Duration window);

    String type();
}
