package com.cloudstorage.service.limiter;

import lombok.Builder;
import lombok.Data;

/**
 * 一次限流判定的结果。retryAfterMs 告诉客户端"最早什么时候可以再来"。
 */
@Data
@Builder
public class RateLimitResult {

    private boolean allowed;
    private int current;
    private int limit;
    private long retryAfterMs;
}
