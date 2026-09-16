package com.cloudstorage.common.exception;

import com.cloudstorage.common.result.ErrorCode;
import lombok.Getter;

/**
 * 业务异常：service 层校验失败时抛出，由 web 层的全局异常处理器统一转成 Result。
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public BusinessException(String message) {
        super(message);
        this.code = ErrorCode.SYSTEM_ERROR.getCode();
    }

    public static BusinessException of(ErrorCode errorCode) {
        return new BusinessException(errorCode);
    }
}
