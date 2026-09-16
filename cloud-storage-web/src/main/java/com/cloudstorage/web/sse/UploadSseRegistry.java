package com.cloudstorage.web.sse;

import com.cloudstorage.service.upload.UploadProgress;
import com.cloudstorage.service.upload.UploadProgressBus;
import com.cloudstorage.service.upload.UploadService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE 进度转发：service 层的进度事件（UploadProgressBus）→ 客户端。
 * 每个 uploadId 可以挂多个连接；连接断开、超时、出错都要摘掉，否则会内存泄漏。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadSseRegistry {

    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    private final UploadProgressBus progressBus;
    private final UploadService uploadService;

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private AutoCloseable subscription;

    @PostConstruct
    void subscribeBus() {
        this.subscription = progressBus.subscribe(this::dispatch);
    }

    @PreDestroy
    void unsubscribeBus() throws Exception {
        if (subscription != null) {
            subscription.close();
        }
    }

    /** 注册一个 SSE 连接；会先校验会话归属，越权直接失败。 */
    public SseEmitter register(Long userId, Long uploadId) {
        uploadService.status(userId, uploadId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.computeIfAbsent(uploadId, key -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(uploadId, emitter));
        emitter.onTimeout(() -> {
            remove(uploadId, emitter);
            emitter.complete();
        });
        emitter.onError(e -> remove(uploadId, emitter));

        // 连上来先推一次当前进度，避免客户端要等到下一个分片才有数据
        progressBus.latest(uploadId).ifPresent(progress -> send(emitter, progress));
        return emitter;
    }

    private void dispatch(UploadProgress progress) {
        List<SseEmitter> list = emitters.get(progress.getUploadId());
        if (list == null || list.isEmpty()) {
            return;
        }
        list.forEach(emitter -> send(emitter, progress));
    }

    private void send(SseEmitter emitter, UploadProgress progress) {
        try {
            emitter.send(SseEmitter.event().name("progress").data(progress, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 连接不可用，移除：uploadId={}", progress.getUploadId());
            remove(progress.getUploadId(), emitter);
        }
    }

    private void remove(Long uploadId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(uploadId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(uploadId);
            }
        }
    }
}
