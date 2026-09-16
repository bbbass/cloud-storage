package com.cloudstorage.web.interceptor;

import com.cloudstorage.common.constant.CommonConstants;
import com.cloudstorage.common.context.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 开发期身份注入：从 X-User-Id / X-Device-Id 请求头取用户与设备，写入 UserContext。
 *
 * 这是 M1 的临时方案（M4 会换成 JWT 解析 + 登录接口）。之所以先做这一步，
 * 是为了让上传链路的"归属校验"从第一天就存在，而不是等鉴权做完再补。
 */
@Slf4j
@Component
public class DevAuthInterceptor implements HandlerInterceptor {

    public static final String HEADER_USER_ID = "X-User-Id";

    @Value("${app.auth.dev-default-user-id:1}")
    private Long devDefaultUserId;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userHeader = request.getHeader(HEADER_USER_ID);
        Long userId = parseUserId(userHeader);
        String deviceId = request.getHeader(CommonConstants.HEADER_DEVICE_ID);
        UserContext.set(new UserContext.LoginUser(userId, null, "dev-user-" + userId, deviceId));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 线程复用，必须清理，否则会串号
        UserContext.clear();
    }

    private Long parseUserId(String header) {
        if (header == null || header.isBlank()) {
            return devDefaultUserId;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            log.warn("非法的 {} 请求头：{}，回落到默认用户", HEADER_USER_ID, header);
            return devDefaultUserId;
        }
    }
}
