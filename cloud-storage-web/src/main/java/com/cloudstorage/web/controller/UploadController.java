package com.cloudstorage.web.controller;

import com.cloudstorage.common.context.UserContext;
import com.cloudstorage.common.result.Result;
import com.cloudstorage.service.upload.UploadService;
import com.cloudstorage.service.upload.dto.CompleteUploadResult;
import com.cloudstorage.service.upload.dto.InitUploadCommand;
import com.cloudstorage.service.upload.dto.InitUploadResult;
import com.cloudstorage.service.upload.dto.PartUploadResult;
import com.cloudstorage.service.upload.dto.UploadStatusView;
import com.cloudstorage.web.dto.InitUploadRequest;
import com.cloudstorage.web.sse.UploadSseRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;

/**
 * 分片上传接口。分片走原始字节流（application/octet-stream），不套 multipart —— 省一次落盘拷贝。
 */
@RestController
@RequestMapping("/api/v1/uploads")
@RequiredArgsConstructor
public class UploadController {

    private final UploadService uploadService;
    private final UploadSseRegistry sseRegistry;

    /** 初始化上传：返回 uploadId 与已上传分片（秒传时 instantUpload=true） */
    @PostMapping
    public Result<InitUploadResult> init(@RequestBody InitUploadRequest request) {
        InitUploadCommand command = InitUploadCommand.builder()
                .fileName(request.getFileName())
                .fileSize(request.getFileSize())
                .sha256(request.getSha256())
                .chunkSize(request.getChunkSize())
                .contentType(request.getContentType())
                .parentId(request.getParentId())
                .build();
        return Result.ok(uploadService.init(UserContext.userId(), UserContext.deviceId(), command));
    }

    /** 上传单个分片，可重复调用（幂等：同一分片号覆盖） */
    @PutMapping("/{uploadId}/parts/{partNo}")
    public Result<PartUploadResult> uploadPart(@PathVariable Long uploadId,
                                               @PathVariable int partNo,
                                               @RequestParam(required = false) Long size,
                                               @RequestParam(required = false) String contentType,
                                               HttpServletRequest request) throws IOException {
        long declaredSize = size == null ? request.getContentLengthLong() : size;
        try (InputStream in = request.getInputStream()) {
            return Result.ok(uploadService.uploadPart(
                    UserContext.userId(), uploadId, partNo, in, declaredSize, contentType));
        }
    }

    /** 合并：服务端 compose + 校验 + 登记，幂等（重复调用返回同一结果） */
    @PostMapping("/{uploadId}/complete")
    public Result<CompleteUploadResult> complete(@PathVariable Long uploadId) {
        return Result.ok(uploadService.complete(UserContext.userId(), uploadId));
    }

    /** 中止并清理分片 */
    @DeleteMapping("/{uploadId}")
    public Result<Void> abort(@PathVariable Long uploadId) {
        uploadService.abort(UserContext.userId(), uploadId);
        return Result.ok();
    }

    /** 查询上传状态与已上传分片（断点续传靠它） */
    @GetMapping("/{uploadId}")
    public Result<UploadStatusView> status(@PathVariable Long uploadId) {
        return Result.ok(uploadService.status(UserContext.userId(), uploadId));
    }

    /** 上传进度 SSE 流 */
    @GetMapping(value = "/{uploadId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter progress(@PathVariable Long uploadId) {
        return sseRegistry.register(UserContext.userId(), uploadId);
    }
}
