package com.cloudstorage.service.upload;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 上传进度总线：service 层产生事件，web 层（SSE）订阅转发。
 * 单机内存实现 —— 多实例部署时需要换成 Redis Pub/Sub，这一点在面试时要能说清。
 */
@Slf4j
@Component
public class UploadProgressBus {

    private final Map<Long, UploadProgress> latest = new ConcurrentHashMap<>();
    private final List<Consumer<UploadProgress>> listeners = new CopyOnWriteArrayList<>();

    public void publish(UploadProgress progress) {
        if (progress == null || progress.getUploadId() == null) {
            return;
        }
        latest.put(progress.getUploadId(), progress);
        for (Consumer<UploadProgress> listener : listeners) {
            try {
                listener.accept(progress);
            } catch (Exception e) {
                log.warn("进度事件投递失败：uploadId={}", progress.getUploadId(), e);
            }
        }
    }

    public Optional<UploadProgress> latest(Long uploadId) {
        return Optional.ofNullable(latest.get(uploadId));
    }

    public AutoCloseable subscribe(Consumer<UploadProgress> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public void clear(Long uploadId) {
        latest.remove(uploadId);
    }
}
