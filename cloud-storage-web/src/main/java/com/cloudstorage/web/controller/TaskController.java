package com.cloudstorage.web.controller;

import com.cloudstorage.common.context.UserContext;
import com.cloudstorage.common.result.Result;
import com.cloudstorage.domain.entity.ProcessingTask;
import com.cloudstorage.service.mq.ProcessingTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 异步任务查询与死信重放。生产环境这类接口要挂管理端鉴权，当前按 X-User-Id 隔离。
 */
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final ProcessingTaskService processingTaskService;

    /** 按文件查任务；不传 fileId 返回当前用户最近的任务 */
    @GetMapping
    public Result<List<ProcessingTask>> list(@RequestParam(required = false) Long fileId) {
        return Result.ok(processingTaskService.list(UserContext.userId(), fileId));
    }

    /** 各状态任务数：观察积压与死信 */
    @GetMapping("/stats")
    public Result<Map<String, Long>> stats() {
        return Result.ok(processingTaskService.stats());
    }

    /** 死信重放：DEAD → PENDING 并重新投递 */
    @PostMapping("/{taskId}/replay")
    public Result<ProcessingTask> replay(@PathVariable Long taskId) {
        return Result.ok(processingTaskService.replay(UserContext.userId(), taskId));
    }
}
