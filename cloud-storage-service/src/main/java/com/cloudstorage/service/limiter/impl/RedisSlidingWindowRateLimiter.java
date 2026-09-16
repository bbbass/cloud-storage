package com.cloudstorage.service.limiter.impl;

import com.cloudstorage.service.limiter.RateLimitResult;
import com.cloudstorage.service.limiter.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 自研滑动窗口限流（Redis ZSET + Lua）。
 *
 * 为什么这么做（面试必问）：
 * 1) 固定窗口计数器在边界处会瞬时放行 2 倍流量；ZSET 以请求时间戳为 score，
 *    每次先 ZREMRANGEBYSCORE 清掉窗口外元素再 ZCARD 计数，窗口是真正滑动的。
 * 2) "清理 + 计数 + 写入"必须原子，否则并发下先读后写会超发；放进 Lua 由 Redis 单线程执行，
 *    顺带省掉了 WATCH/MULTI 的重试逻辑。
 * 3) 代价是内存 O(limit)，所以 key 粒度要收敛（场景 + 维度 + 主体），并给 key 设窗口 +1s 过期兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisSlidingWindowRateLimiter implements RateLimiter {

    private static final String SCRIPT = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]

            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
            local count = redis.call('ZCARD', key)
            if count < limit then
                redis.call('ZADD', key, now, member)
                redis.call('PEXPIRE', key, window + 1000)
                return {1, count + 1, 0}
            end

            local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
            local retry = window
            if oldest[2] then
                retry = (tonumber(oldest[2]) + window) - now
                if retry < 0 then retry = 0 end
            end
            redis.call('PEXPIRE', key, window + 1000)
            return {0, count, retry}
            """;

    private static final DefaultRedisScript<List> RATE_LIMIT_SCRIPT =
            new DefaultRedisScript<>(SCRIPT, List.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public RateLimitResult acquire(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        // 成员唯一，避免同一毫秒内的多次请求互相覆盖导致计数偏少
        String member = now + "-" + ThreadLocalRandom.current().nextLong(1_000_000);
        List<?> result = redisTemplate.execute(RATE_LIMIT_SCRIPT, Collections.singletonList(key),
                String.valueOf(now),
                String.valueOf(window.toMillis()),
                String.valueOf(limit),
                member);
        if (result == null || result.size() < 3) {
            // 脚本异常时放行但不静默：限流挂掉不应该阻断主业务
            log.warn("限流脚本返回异常，本次放行：key={}, result={}", key, result);
            return RateLimitResult.builder().allowed(true).current(0).limit(limit).build();
        }
        return RateLimitResult.builder()
                .allowed(toLong(result.get(0)) == 1L)
                .current((int) toLong(result.get(1)))
                .limit(limit)
                .retryAfterMs(toLong(result.get(2)))
                .build();
    }

    @Override
    public String type() {
        return "redis-zset-sliding-window";
    }

    private long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }
}
