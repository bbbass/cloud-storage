package com.cloudstorage.service.quota.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.cloudstorage.common.exception.BusinessException;
import com.cloudstorage.common.result.ErrorCode;
import com.cloudstorage.domain.dto.QuotaSnapshot;
import com.cloudstorage.domain.entity.QuotaUsage;
import com.cloudstorage.domain.mapper.QuotaUsageMapper;
import com.cloudstorage.domain.mapper.UploadSessionMapper;
import com.cloudstorage.service.quota.QuotaProperties;
import com.cloudstorage.service.quota.QuotaService;
import com.cloudstorage.service.quota.dto.QuotaView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配额实现。
 *
 * 设计要点：
 * 1) 预占用 Lua 在 Redis 内原子完成"读 + 判断 + 累加"，避免 check-then-act 在并发下超卖；
 * 2) DB 的 quota_usage.used_bytes 是事实源，由上传完成时在同一个事务里累加；
 * 3) Redis 计数 = 已提交用量 + 进行中会话的预占，所以对账时用
 *    SUM(files.size) + SUM(进行中 upload_sessions.file_size) 重算，任一侧漂移都能修回来。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaServiceImpl implements QuotaService {

    private static final String RESERVE_SCRIPT = """
            local used = tonumber(redis.call('GET', KEYS[1]) or '0')
            local size = tonumber(ARGV[1])
            local limit = tonumber(ARGV[2])
            if used + size > limit then
                return {0, used}
            end
            local newUsed = redis.call('INCRBY', KEYS[1], size)
            return {1, newUsed}
            """;

    private static final DefaultRedisScript<List> RESERVE = new DefaultRedisScript<>(RESERVE_SCRIPT, List.class);

    private final QuotaUsageMapper quotaUsageMapper;
    private final UploadSessionMapper uploadSessionMapper;
    private final StringRedisTemplate redisTemplate;
    private final QuotaProperties quotaProperties;

    @Override
    public QuotaView getQuota(Long userId) {
        QuotaUsage usage = ensureQuota(userId);
        long used = currentUsed(userId, usage);
        return QuotaView.builder()
                .userId(userId)
                .quotaBytes(usage.getQuotaBytes())
                .usedBytes(used)
                .availableBytes(Math.max(0, usage.getQuotaBytes() - used))
                .build();
    }

    @Override
    public void reserve(Long userId, long size) {
        if (size <= 0) {
            return;
        }
        QuotaUsage usage = ensureQuota(userId);
        List<?> result = redisTemplate.execute(RESERVE, Collections.singletonList(usedKey(userId)),
                String.valueOf(size), String.valueOf(usage.getQuotaBytes()));
        if (result == null || result.size() < 2) {
            log.warn("配额预占脚本返回异常，本次放行：userId={}", userId);
            return;
        }
        if (toLong(result.get(0)) != 1L) {
            long used = toLong(result.get(1));
            throw new BusinessException(ErrorCode.QUOTA_EXCEEDED, String.format(
                    "存储配额不足：已用 %.2f MB / 上限 %.2f MB，本次需要 %.2f MB",
                    used / 1024.0 / 1024, usage.getQuotaBytes() / 1024.0 / 1024, size / 1024.0 / 1024));
        }
    }

    @Override
    public void commit(Long userId, long size) {
        if (size <= 0) {
            return;
        }
        quotaUsageMapper.addUsed(userId, size);
    }

    @Override
    public void release(Long userId, long size) {
        if (size <= 0) {
            return;
        }
        Long remaining = redisTemplate.opsForValue().decrement(usedKey(userId), size);
        if (remaining != null && remaining < 0) {
            // 预占被重复释放或 Redis 数据丢失：兜底归零，等对账修正
            redisTemplate.opsForValue().set(usedKey(userId), "0");
        }
    }

    @Override
    public int reconcile() {
        // ① DB 事实源：按 files 汇总重算 used_bytes
        quotaUsageMapper.reconcileUsedBytesFromFiles();

        // ② Redis 快路径：= 已提交 + 进行中会话预占
        Map<Long, Long> pending = new HashMap<>();
        for (QuotaSnapshot snapshot : uploadSessionMapper.sumPendingByUser()) {
            pending.put(snapshot.getUserId(),
                    snapshot.getPendingBytes() == null ? 0L : snapshot.getPendingBytes());
        }
        List<Long> userIds = quotaUsageMapper.selectAllUserIds();
        for (Long userId : userIds) {
            long expected = defaultLong(dbUsedBytes(userId)) + pending.getOrDefault(userId, 0L);
            redisTemplate.opsForValue().set(usedKey(userId), String.valueOf(expected));
        }
        log.info("配额对账完成：用户数={}", userIds.size());
        return userIds.size();
    }

    /* ------------------------------ 内部方法 ------------------------------ */

    private QuotaUsage ensureQuota(Long userId) {
        QuotaUsage usage = selectQuota(userId);
        if (usage != null) {
            return usage;
        }
        QuotaUsage created = new QuotaUsage();
        created.setUserId(userId);
        created.setQuotaBytes(quotaProperties.getDefaultQuotaBytes());
        created.setUsedBytes(0L);
        try {
            quotaUsageMapper.insert(created);
            return created;
        } catch (DuplicateKeyException e) {
            return selectQuota(userId);
        }
    }

    private QuotaUsage selectQuota(Long userId) {
        return quotaUsageMapper.selectList(Wrappers.<QuotaUsage>lambdaQuery()
                        .eq(QuotaUsage::getUserId, userId)
                        .last("limit 1"))
                .stream().findFirst().orElse(null);
    }

    private Long dbUsedBytes(Long userId) {
        QuotaUsage usage = selectQuota(userId);
        return usage == null ? 0L : usage.getUsedBytes();
    }

    private long currentUsed(Long userId, QuotaUsage usage) {
        String cached = redisTemplate.opsForValue().get(usedKey(userId));
        if (cached != null) {
            return defaultLong(Long.valueOf(cached));
        }
        long recomputed = defaultLong(usage.getUsedBytes()) + uploadSessionMapper.sumPendingBytesByUser(userId);
        redisTemplate.opsForValue().set(usedKey(userId), String.valueOf(recomputed));
        return recomputed;
    }

    private String usedKey(Long userId) {
        return quotaProperties.getKeyPrefix() + ":used:" + userId;
    }

    private long defaultLong(Long value) {
        return value == null ? 0L : value;
    }

    private long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }
}
