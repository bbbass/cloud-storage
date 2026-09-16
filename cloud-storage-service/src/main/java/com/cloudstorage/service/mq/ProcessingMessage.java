package com.cloudstorage.service.mq;

import com.cloudstorage.domain.entity.ProcessingTask;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 消息体：只传 taskId，消费端回表取详情 —— 消息尽量小，且以 DB 为事实源。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProcessingMessage {

    private Long taskId;
    private Long fileId;
    private String taskType;

    public static ProcessingMessage of(ProcessingTask task) {
        return new ProcessingMessage(task.getId(), task.getFileId(), task.getTaskType());
    }

    /** 消息 key 用 fileId：同一文件的任务落到同一队列，避免同文件任务并发乱序 */
    public String messageKey() {
        return String.valueOf(fileId);
    }
}
