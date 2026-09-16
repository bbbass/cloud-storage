package com.cloudstorage.service.limiter.lock;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 分布式锁抽象。项目里的用途很具体：缓存重建的互斥、对账任务的单飞（single-flight）。
 */
public interface DistributedLock {

    /** 获取锁；成功返回 token（释放时要带上），失败返回 null */
    String tryLock(String key, Duration ttl);

    /** 释放锁：比较 token 后再删，避免超时后误删别人的锁 */
    boolean unlock(String key, String token);

    /** 拿不到锁返回 empty，不阻塞、不抛异常 */
    <T> Optional<T> execute(String key, Duration ttl, Supplier<T> action);

    String type();
}
