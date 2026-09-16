package com.cloudstorage.common.result;

import lombok.Getter;

/**
 * 全局错误码。分段规则见 AGENTS.md 6.1：
 * 10xxx 通用、20xxx 用户与租户、30xxx 文件与上传、40xxx 相册、50xxx 限流与配额。
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "成功"),

    /* ---------- 10xxx 通用 ---------- */
    PARAM_ERROR(10001, "参数错误"),
    UNAUTHORIZED(10002, "未认证或登录已过期"),
    FORBIDDEN(10003, "无权访问"),
    NOT_FOUND(10004, "资源不存在"),
    METHOD_NOT_ALLOWED(10005, "请求方法不支持"),
    SYSTEM_ERROR(10999, "系统繁忙，请稍后再试"),

    /* ---------- 20xxx 用户与租户 ---------- */
    USER_NOT_FOUND(20001, "用户不存在"),
    PASSWORD_ERROR(20002, "用户名或密码错误"),
    USER_DISABLED(20003, "账号已被禁用"),
    TOKEN_INVALID(20004, "凭证无效"),

    /* ---------- 30xxx 文件与上传 ---------- */
    FILE_NOT_FOUND(30001, "文件不存在"),
    UPLOAD_SESSION_NOT_FOUND(30002, "上传会话不存在或已过期"),
    UPLOAD_PART_MISSING(30003, "分片缺失，无法合并"),
    UPLOAD_HASH_MISMATCH(30004, "文件校验失败，请重传"),
    UPLOAD_CHUNK_OUT_OF_RANGE(30005, "分片序号越界"),
    STORAGE_ERROR(30006, "存储服务异常"),
    UPLOAD_MERGING(30007, "文件正在合并中，请稍后查询结果"),
    UPLOAD_STATUS_INVALID(30008, "当前上传状态不允许该操作"),

    /* ---------- 40xxx 相册 ---------- */
    ALBUM_NOT_FOUND(40001, "相册不存在"),
    ALBUM_NO_PERMISSION(40002, "无权操作该相册"),

    /* ---------- 50xxx 限流与配额 ---------- */
    RATE_LIMITED(50001, "请求过于频繁，请稍后再试"),
    QUOTA_EXCEEDED(50002, "存储配额不足"),
    DOWNLOAD_FORBIDDEN(50003, "下载被拒绝");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
