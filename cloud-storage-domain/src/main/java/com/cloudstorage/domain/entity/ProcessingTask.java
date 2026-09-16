package com.cloudstorage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 异步处理任务，同时充当"本地消息表"：先落库，再投递 MQ；投递失败由补偿任务扫出来重投。
 * 状态机：PENDING → RUNNING → DONE / DEAD（死信）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("processing_tasks")
public class ProcessingTask extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private Long fileId;
    private Long objectId;
    private Long userId;
    /** THUMBNAIL / EXIF / ... */
    private String taskType;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetryTime;
    private String errorMsg;
    private String result;
    private Long costMs;
}
