package com.cloudstorage.service.mq;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.cloudstorage.common.exception.BusinessException;
import com.cloudstorage.common.result.ErrorCode;
import com.cloudstorage.domain.entity.ProcessingTask;
import com.cloudstorage.domain.enums.TaskStatus;
import com.cloudstorage.domain.mapper.ProcessingTaskMapper;
import com.cloudstorage.service.processor.TaskTypes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务生命周期管理：建任务（本地消息表）、查询、统计、死信重放。
 * 上传完成时调用 enqueueForFile：先落库（事务内），提交后再投递消息（事务外）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessingTaskService {

    /** 当前只对图片建任务；视频/其它类型的处理器属于"预留 SPI"，不建任务避免空跑 */
    private static final List<String> IMAGE_TASKS = List.of(TaskTypes.THUMBNAIL, TaskTypes.EXIF);

    private final ProcessingTaskMapper taskMapper;
    private final MessagePublisher messagePublisher;

    /** 建任务 + 投递；uk(file_id, task_type) 保证重复调用不会重复建 */
    public List<ProcessingTask> enqueueForFile(Long userId, Long fileId, Long objectId, String contentType) {
        if (contentType == null || !contentType.startsWith("image/")) {
            return List.of();
        }
        List<ProcessingTask> created = new ArrayList<>();
        for (String taskType : IMAGE_TASKS) {
            ProcessingTask task = new ProcessingTask();
            task.setFileId(fileId);
            task.setObjectId(objectId);
            task.setUserId(userId);
            task.setTaskType(taskType);
            task.setStatus(TaskStatus.PENDING.name());
            task.setRetryCount(0);
            try {
                taskMapper.insert(task);
                created.add(task);
            } catch (DuplicateKeyException e) {
                log.debug("任务已存在，跳过：fileId={}, taskType={}", fileId, taskType);
            }
        }
        // 落库成功后再投递：投递失败任务仍是 PENDING，补偿任务会兜底
        created.forEach(task -> messagePublisher.publish(ProcessingMessage.of(task), 0));
        log.info("已登记异步任务 {} 个：fileId={}, types={}", created.size(), fileId,
                created.stream().map(ProcessingTask::getTaskType).toList());
        return created;
    }

    public List<ProcessingTask> list(Long userId, Long fileId) {
        return taskMapper.selectList(Wrappers.<ProcessingTask>lambdaQuery()
                .eq(ProcessingTask::getUserId, userId)
                .eq(fileId != null, ProcessingTask::getFileId, fileId)
                .orderByDesc(ProcessingTask::getId));
    }

    public Map<String, Long> stats() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("PENDING", 0L);
        result.put("RUNNING", 0L);
        result.put("DONE", 0L);
        result.put("DEAD", 0L);
        for (Map<String, Object> row : taskMapper.countGroupByStatus()) {
            String status = String.valueOf(row.get("status"));
            Object count = row.get("cnt");
            result.put(status, count instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(count)));
        }
        return result;
    }

    /** 死信重放：DEAD → PENDING，重试次数清零并重新投递 */
    public ProcessingTask replay(Long userId, Long taskId) {
        ProcessingTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该任务");
        }
        if (taskMapper.casReplay(taskId) != 1) {
            throw new BusinessException(ErrorCode.UPLOAD_STATUS_INVALID,
                    "只有死信任务可以重放，当前状态：" + task.getStatus());
        }
        task = taskMapper.selectById(taskId);
        messagePublisher.publish(ProcessingMessage.of(task), 0);
        log.info("死信重放：taskId={}, type={}, fileId={}", taskId, task.getTaskType(), task.getFileId());
        return task;
    }
}
