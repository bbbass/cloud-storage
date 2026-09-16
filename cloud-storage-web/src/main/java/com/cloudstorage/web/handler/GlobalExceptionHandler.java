package com.cloudstorage.web.handler;

import com.cloudstorage.common.exception.BusinessException;
import com.cloudstorage.common.exception.RateLimitException;
import com.cloudstorage.common.result.ErrorCode;
import com.cloudstorage.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常兜底：业务异常按错误码返回，其余异常统一 10999 并打日志。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常：code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 限流单独处理：业务错误返回 HTTP 200 + 错误码，但限流必须让调用方在传输层就能识别，
     * 所以返回 429 并带上 Retry-After（秒）。
     */
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Result<Void>> handleRateLimit(RateLimitException e) {
        log.warn("触发限流：scene={}, dimension={}, retryAfter={}ms",
                e.getScene(), e.getDimension(), e.getRetryAfterMs());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .body(Result.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数异常：{}", e.getMessage());
        return Result.fail(ErrorCode.PARAM_ERROR, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }
}
