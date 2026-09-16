package com.cloudstorage.service.limiter;

import com.cloudstorage.common.context.UserContext;
import com.cloudstorage.common.exception.RateLimitException;
import com.cloudstorage.service.limiter.annotation.RateLimit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 限流切面：把 @RateLimit 声明的场景展开为"用户 + 设备"两个维度的滑动窗口判定。
 * 两个维度都通过才执行目标方法；任一维度被拒就抛 RateLimitException（web 层转 429）。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RateLimiter rateLimiter;
    private final LimiterProperties limiterProperties;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        if (!limiterProperties.isEnabled()) {
            return joinPoint.proceed();
        }
        RateLimitScene scene = rateLimit.scene();
        LimiterProperties.Rule rule = limiterProperties.ruleOf(scene);

        Long userId = UserContext.userId();
        if (userId != null) {
            check(scene, Dimension.USER, String.valueOf(userId), rule.userLimit(), rule.window());
        }
        String deviceId = UserContext.deviceId();
        if (deviceId != null && !deviceId.isBlank()) {
            check(scene, Dimension.DEVICE, deviceId, rule.deviceLimit(), rule.window());
        }
        return joinPoint.proceed();
    }

    private void check(RateLimitScene scene, Dimension dimension, String subject, int limit, Duration window) {
        String key = limiterProperties.getKeyPrefix() + ":" + scene.key()
                + ":" + dimension.name().toLowerCase() + ":" + subject;
        RateLimitResult result = rateLimiter.acquire(key, limit, window);
        if (!result.isAllowed()) {
            log.warn("触发限流：scene={}, dimension={}, subject={}, current={}/{}",
                    scene.key(), dimension.name(), subject, result.getCurrent(), result.getLimit());
            throw new RateLimitException(scene.label(), dimension.label(), result.getRetryAfterMs());
        }
    }
}
