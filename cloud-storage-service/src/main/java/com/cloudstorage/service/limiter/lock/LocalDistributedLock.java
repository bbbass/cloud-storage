package com.cloudstorage.service.limiter.lock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 单机锁兜底（app.redis.enabled=false）。多实例下它保护不了任何东西，仅用于本地开发。
 */
@Service
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "false", matchIfMissing = true)
public class LocalDistributedLock implements DistributedLock {

    private record Holder(String token, long expireAt) {
    }

    private final Map<String, Holder> locks = new ConcurrentHashMap<>();

    @Override
    public String tryLock(String key, Duration ttl) {
        long now = System.currentTimeMillis();
        String token = UUID.randomUUID().toString();
        Holder holder = new Holder(token, now + ttl.toMillis());
        Holder existing = locks.compute(key, (k, current) ->
                (current == null || current.expireAt() < now) ? holder : current);
        return existing.token().equals(token) ? token : null;
    }

    @Override
    public boolean unlock(String key, String token) {
        Holder holder = locks.get(key);
        if (holder != null && holder.token().equals(token)) {
            locks.remove(key);
            return true;
        }
        return false;
    }

    @Override
    public <T> Optional<T> execute(String key, Duration ttl, Supplier<T> action) {
        String token = tryLock(key, ttl);
        if (token == null) {
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
        return "local-map";
    }
}
