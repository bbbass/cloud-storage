package com.cloudstorage.web.controller;

import com.cloudstorage.common.result.Result;
import com.cloudstorage.service.cache.FileMetaCache;
import com.cloudstorage.service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查 / 自检接口：验证 web → service 装配、存储实现、数据库连通性。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HealthController {

    private final StorageService storageService;
    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;
    private final FileMetaCache fileMetaCache;

    @Value("${app.redis.enabled:false}")
    private boolean redisEnabled;

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("app", "cloud-storage");
        data.put("time", LocalDateTime.now());
        data.put("storageType", storageService.type());
        data.put("db", checkDb());
        data.put("redis", checkRedis());
        data.put("cache", fileMetaCache.stats());
        return Result.ok(data);
    }

    /** app.redis.enabled=false 时完全不碰 Redis；开着才探活。 */
    private String checkRedis() {
        if (!redisEnabled) {
            return "disabled";
        }
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            return "PONG".equalsIgnoreCase(connection.ping()) ? "up" : "unexpected";
        } catch (Exception e) {
            log.warn("Redis 探活失败：{}", e.getMessage());
            return "down: " + e.getMessage();
        }
    }

    /** 只做快速探活，不抛异常：数据库不可用时返回 down，方便定位环境问题。 */
    private String checkDb() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2)
                    ? connection.getMetaData().getDatabaseProductName() + " " + connection.getMetaData().getDatabaseProductVersion()
                    : "down";
        } catch (Exception e) {
            log.warn("数据库探活失败：{}", e.getMessage());
            return "down: " + e.getMessage();
        }
    }
}
