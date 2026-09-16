package com.cloudstorage.domain.enums;

/**
 * 异步任务状态。DEAD 就是死信：重试次数耗尽，需要人工/接口重放。
 */
public enum TaskStatus {

    PENDING,
    RUNNING,
    DONE,
    DEAD;

    public static boolean isTerminal(String status) {
        return DONE.name().equals(status) || DEAD.name().equals(status);
    }
}
