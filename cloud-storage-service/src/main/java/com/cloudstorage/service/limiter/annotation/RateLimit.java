package com.cloudstorage.service.limiter.annotation;

import com.cloudstorage.service.limiter.RateLimitScene;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 在方法上声明限流场景，由 RateLimitAspect 生效。
 * 用户维度与设备维度各判一次（设备维度取请求头 X-Device-Id）。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    RateLimitScene scene();
}
