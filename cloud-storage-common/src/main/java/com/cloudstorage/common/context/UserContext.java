package com.cloudstorage.common.context;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 当前登录用户的线程上下文。web 层（拦截器/过滤器）负责写入与清理，service 层只读。
 */
public final class UserContext {

    private UserContext() {
    }

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static Long userId() {
        LoginUser user = HOLDER.get();
        return user == null ? null : user.getUserId();
    }

    public static Long tenantId() {
        LoginUser user = HOLDER.get();
        return user == null ? null : user.getTenantId();
    }

    public static String deviceId() {
        LoginUser user = HOLDER.get();
        return user == null ? null : user.getDeviceId();
    }

    /** 必须在请求结束时调用，避免线程池复用导致串号。 */
    public static void clear() {
        HOLDER.remove();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginUser implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long userId;
        private Long tenantId;
        private String username;
        private String deviceId;
    }
}
