package com.cloudstorage.service.limiter;

import com.cloudstorage.service.limiter.impl.LocalSlidingWindowRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 滑动窗口限流算法的单元测试（本地实现，语义与 Redis+Lua 版一致）。
 * 重点验证：窗口内不超发、被拒时给出重试时间、窗口滑动后恢复放行。
 */
class LocalSlidingWindowRateLimiterTest {

    private final RateLimiter limiter = new LocalSlidingWindowRateLimiter();

    @Test
    @DisplayName("窗口内放行 limit 次，第 limit+1 次被拒")
    void allowsUpToLimitThenRejects() {
        String key = "test:user:1";
        Duration window = Duration.ofSeconds(60);

        for (int i = 1; i <= 3; i++) {
            RateLimitResult result = limiter.acquire(key, 3, window);
            assertThat(result.isAllowed()).as("第 %d 次应放行", i).isTrue();
            assertThat(result.getCurrent()).isEqualTo(i);
        }

        RateLimitResult rejected = limiter.acquire(key, 3, window);
        assertThat(rejected.isAllowed()).isFalse();
        assertThat(rejected.getCurrent()).isEqualTo(3);
        assertThat(rejected.getRetryAfterMs()).isGreaterThan(0);
    }

    @Test
    @DisplayName("窗口滑过后重新放行（这是滑动窗口相对固定窗口的关键差异）")
    void allowsAgainAfterWindowSlides() throws InterruptedException {
        String key = "test:user:2";
        Duration window = Duration.ofMillis(300);

        assertThat(limiter.acquire(key, 1, window).isAllowed()).isTrue();
        assertThat(limiter.acquire(key, 1, window).isAllowed()).isFalse();

        Thread.sleep(350);
        assertThat(limiter.acquire(key, 1, window).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("不同 key 之间互不影响")
    void keysAreIsolated() {
        Duration window = Duration.ofSeconds(60);
        assertThat(limiter.acquire("test:user:3", 1, window).isAllowed()).isTrue();
        assertThat(limiter.acquire("test:user:4", 1, window).isAllowed()).isTrue();
        assertThat(limiter.acquire("test:user:3", 1, window).isAllowed()).isFalse();
    }
}
