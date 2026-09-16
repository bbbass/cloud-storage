package com.cloudstorage.common.result;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 统一响应体：所有 Controller 出参都用它包一层。
 */
@Data
@Accessors(chain = true)
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private int code;
    private String message;
    private T data;
    private long timestamp = System.currentTimeMillis();

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<T>()
                .setCode(ErrorCode.SUCCESS.getCode())
                .setMessage(ErrorCode.SUCCESS.getMessage())
                .setData(data);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return fail(errorCode.getCode(), errorCode.getMessage());
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return fail(errorCode.getCode(), message);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<T>()
                .setCode(code)
                .setMessage(message);
    }

    public boolean isSuccess() {
        return this.code == ErrorCode.SUCCESS.getCode();
    }
}
