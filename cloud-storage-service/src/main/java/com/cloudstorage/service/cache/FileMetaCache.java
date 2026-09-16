package com.cloudstorage.service.cache;

import com.cloudstorage.common.exception.BusinessException;
import com.cloudstorage.common.result.ErrorCode;
import com.cloudstorage.domain.entity.FileItem;
import com.cloudstorage.domain.entity.FileObject;
import com.cloudstorage.domain.mapper.FileItemMapper;
import com.cloudstorage.domain.mapper.FileObjectMapper;
import com.cloudstorage.service.limiter.RateLimitScene;
import com.cloudstorage.service.limiter.annotation.RateLimit;
import com.cloudstorage.service.limiter.lock.DistributedLock;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 文件元数据缓存，覆盖缓存三大经典问题：
 * - 穿透：DB 里也没有的 id，缓存空值标记（短 TTL，避免被随机 id 打穿）；
 * - 击穿：热点 key 过期瞬间用分布式锁做互斥重建，失败方短暂等待后重读，保证"最多一次回源"；
 * - 雪崩：TTL 加随机抖动，避免同一批 key 同时失效。
 * 另外缓存里带着 userId，读取时校验归属，防止拿别人的 fileId 探测（越权直接按不存在处理）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileMetaCache {

    private static final String KEY_PREFIX = "cs:filemeta:";
    private static final String NULL_MARKER = "__NULL__";
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration NULL_TTL = Duration.ofSeconds(30);
    private static final Duration LOCK_TTL = Duration.ofSeconds(5);
    private static final long WAIT_BEFORE_RETRY_MS = 30L;

    private final StringRedisTemplate redisTemplate;
    private final FileItemMapper fileItemMapper;
    private final FileObjectMapper fileObjectMapper;
    private final DistributedLock distributedLock;
    private final ObjectMapper objectMapper;

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong nullHits = new AtomicLong();
    private final AtomicLong rebuilds = new AtomicLong();
    private final AtomicLong fallbackReads = new AtomicLong();

    @RateLimit(scene = RateLimitScene.FILE_META)
    public FileMeta get(Long userId, Long fileId) {
        String key = KEY_PREFIX + fileId;

        String cached = redisTemplate.opsForValue().get(key);
        if (NULL_MARKER.equals(cached)) {
            nullHits.incrementAndGet();
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        // 反序列化失败（缓存内容损坏）按未命中处理，走下面的重建流程
        FileMeta cachedMeta = cached == null ? null : deserialize(cached);
        if (cachedMeta != null) {
            hits.incrementAndGet();
            return requireOwned(cachedMeta, userId);
        }

        misses.incrementAndGet();
        String lockKey = key + ":lock";
        String token = distributedLock.tryLock(lockKey, LOCK_TTL);
        if (token == null) {
            // 没抢到锁：热点 key 正在被别人重建，短暂等待后重读，仍没有就直接回源（可用性优先）
            fallbackReads.incrementAndGet();
            sleepQuietly();
            String retry = redisTemplate.opsForValue().get(key);
            FileMeta retried = (retry == null || NULL_MARKER.equals(retry)) ? null : deserialize(retry);
            if (retried != null) {
                hits.incrementAndGet();
                return requireOwned(retried, userId);
            }
            return requireOwned(loadFromDb(fileId), userId);
        }

        try {
            // 双重检查：等锁期间可能已经有人填好了
            String second = redisTemplate.opsForValue().get(key);
            FileMeta rebuiltByOther = (second == null || NULL_MARKER.equals(second)) ? null : deserialize(second);
            if (rebuiltByOther != null) {
                hits.incrementAndGet();
                return requireOwned(rebuiltByOther, userId);
            }
            rebuilds.incrementAndGet();
            FileMeta meta = loadFromDb(fileId);
            if (meta == null) {
                redisTemplate.opsForValue().set(key, NULL_MARKER, NULL_TTL);
                throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
            }
            long jitter = ThreadLocalRandom.current().nextLong(0, 60);
            redisTemplate.opsForValue().set(key, serialize(meta), TTL.plusSeconds(jitter));
            return requireOwned(meta, userId);
        } finally {
            distributedLock.unlock(lockKey, token);
        }
    }

    /** 文件变更时调用（M4 的删除/重命名接口会用到） */
    public void evict(Long fileId) {
        redisTemplate.delete(KEY_PREFIX + fileId);
    }

    public CacheStats stats() {
        long h = hits.get();
        long m = misses.get();
        long total = h + m;
        return CacheStats.builder()
                .hits(h)
                .misses(m)
                .nullHits(nullHits.get())
                .rebuilds(rebuilds.get())
                .fallbackReads(fallbackReads.get())
                .hitRate(total == 0 ? 0d : Math.round(h * 10000.0 / total) / 100.0)
                .build();
    }

    private FileMeta requireOwned(FileMeta meta, Long userId) {
        if (meta == null || !meta.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        return meta;
    }

    private FileMeta loadFromDb(Long fileId) {
        FileItem item = fileItemMapper.selectById(fileId);
        if (item == null) {
            return null;
        }
        FileObject object = fileObjectMapper.selectById(item.getObjectId());
        return FileMeta.builder()
                .fileId(item.getId())
                .userId(item.getUserId())
                .objectId(item.getObjectId())
                .fileName(item.getFileName())
                .size(item.getSize())
                .sha256(item.getSha256())
                .contentType(item.getContentType())
                .objectKey(object == null ? null : object.getObjectKey())
                .createTime(item.getCreateTime())
                .build();
    }

    private String serialize(FileMeta meta) {
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "序列化文件元数据失败");
        }
    }

    private FileMeta deserialize(String json) {
        try {
            return objectMapper.readValue(json, FileMeta.class);
        } catch (Exception e) {
            // 缓存内容坏了就当作未命中，让下面的流程重建
            log.warn("缓存反序列化失败，按未命中处理：{}", e.getMessage());
            return null;
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(WAIT_BEFORE_RETRY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
