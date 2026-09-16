package com.cloudstorage.service.processor;

/**
 * 任务类型常量。处理器用 SPI 注册，新增类型只要实现 ProcessingHandler 并声明自己的 taskType。
 */
public final class TaskTypes {

    private TaskTypes() {
    }

    public static final String THUMBNAIL = "THUMBNAIL";
    public static final String EXIF = "EXIF";
}
