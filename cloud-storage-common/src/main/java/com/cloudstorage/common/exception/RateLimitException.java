package com.cloudstorage.common.exception;

import com.cloudstorage.common.result.ErrorCode;
import lombok.Getter;

/**
 * 限流异常：带上"多久之后可以重试"，web 层据此返回 429 + Retry-After。
 */
@Getter
public class RateLimitException extends BusinessException {

    private static final long serialVersionUID = 1L;

    private final String scene;
    private final String dimension;
    private final long retryAfterMs;

    public RateLimitException(String scene, String dimension, long retryAfterMs) {
        super(ErrorCode.RATE_LIMITED, String.format("触发限流：%s（%s 维度），请 %.1f 秒后重试",
                scene, dimension, Math.max(retryAfterMs, 0) / 1000.0));
        this.scene = scene;
        this.dimension = dimension;
        this.retryAfterMs = Math.max(retryAfterMs, 0);
    }

    /** 向上取整到秒，用于 HTTP Retry-After */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfterMs + 999) / 1000);
    }
}
