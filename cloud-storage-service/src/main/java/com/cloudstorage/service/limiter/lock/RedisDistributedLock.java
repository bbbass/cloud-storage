package com.cloudstorage.service.limiter.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 基于 Redis 的分布式锁。
 *
 * 实现要点（面试会问）：
 * 1) 加锁用 SET key token NX PX ttl：一条命令同时完成"不存在才写 + 设过期"，
 *    避免 SETNX + EXPIRE 两步在第二步失败时死锁。
 * 2) 解锁必须"比对 token 再删"（Lua），否则持有者超时后会把别人的锁删掉。
 * 3) 本实现固定 TTL、不自动续期，适合短任务（缓存重建、对账）。长任务要么业务侧续期，
 *    要么换 Redisson 看门狗；这里刻意不引 Redisson，保持限流/锁都是自研。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisDistributedLock implements DistributedLock {

    private static final String UNLOCK_SCRIPT = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """;

    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public String tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(ok) ? token : null;
    }

    @Override
    public boolean unlock(String key, String token) {
        if (token == null) {
            return false;
        }
        Long deleted = redisTemplate.execute(UNLOCK, Collections.singletonList(key), token);
        return deleted != null && deleted > 0;
    }

    @Override
    public <T> Optional<T> execute(String key, Duration ttl, Supplier<T> action) {
        String token = tryLock(key, ttl);
        if (token == null) {
            log.debug("未获取到锁，跳过：{}", key);
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(action.get());
        } finally {
            unlock(key, token);
        }
    }

    @Override
    public String type() {
        return "redis-setnx";
    }
}
