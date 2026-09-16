package com.cloudstorage.service.processor;

/**
 * 处理处理器 SPI：新增一种异步处理（缩略图、EXIF、以后的索引）只要实现它，
 * 由 Spring 自动收集进 ProcessingHandlerRegistry，不需要改调度代码。
 */
public interface ProcessingHandler {

    /** 与 processing_tasks.task_type 对应 */
    String taskType();

    /** 执行处理；结果写回 context.setResult(...)，异常交给调度器统一重试 / 入死信 */
    void handle(ProcessingContext context);
}
