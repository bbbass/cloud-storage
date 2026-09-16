package com.cloudstorage.service.limiter;

/**
 * 限流维度。产品要求：单设备不能占满带宽、单用户不能被刷、租户/家庭共享上限。
 */
public enum Dimension {

    USER("用户"),
    DEVICE("设备");

    private final String label;

    Dimension(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
