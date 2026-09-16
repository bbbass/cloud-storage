package com.cloudstorage.service.limiter;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 限流配置：app.limiter.*
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.limiter")
public class LimiterProperties {

    private boolean enabled = true;

    /** Redis key 前缀，避免与同实例上其它项目/环境冲突 */
    private String keyPrefix = "cs:rl";

    /** 覆盖默认阈值：key 用场景的 kebab 名，如 upload-part */
    private Map<String, SceneRule> scenes = new LinkedHashMap<>();

    public Rule ruleOf(RateLimitScene scene) {
        SceneRule override = scenes.get(scene.key());
        int userLimit = override != null && override.getUserLimit() != null ? override.getUserLimit() : scene.userLimit();
        int deviceLimit = override != null && override.getDeviceLimit() != null
                ? override.getDeviceLimit() : scene.deviceLimit();
        Duration window = override != null && override.getWindow() != null ? override.getWindow() : scene.window();
        return new Rule(userLimit, deviceLimit, window);
    }

    @Data
    public static class SceneRule {
        private Integer userLimit;
        private Integer deviceLimit;
        private Duration window;
    }

    public record Rule(int userLimit, int deviceLimit, Duration window) {
    }
}
