package com.cloudstorage.common.constant;

/**
 * 全局常量：分页默认值、请求头名、MDC key。
 */
public final class CommonConstants {

    private CommonConstants() {
    }

    /* 分页 */
    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 200;

    /* 请求头 */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    public static final String HEADER_DEVICE_ID = "X-Device-Id";
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String TOKEN_PREFIX = "Bearer ";

    /* MDC */
    public static final String TRACE_ID_MDC_KEY = "traceId";

    /* 对象存储 key 分隔符 */
    public static final char KEY_SEPARATOR = '/';
}
