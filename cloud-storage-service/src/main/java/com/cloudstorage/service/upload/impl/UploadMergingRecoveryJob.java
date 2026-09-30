package com.cloudstorage.service.upload.impl;

import com.cloudstorage.domain.entity.UploadSession;
import com.cloudstorage.domain.mapper.UploadSessionMapper;
import com.cloudstorage.service.upload.UploadProperties;
import com.cloudstorage.service.upload.UploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MERGING 卡死恢复：compose 外部 IO 不能通过数据库事务回滚，进程被 kill 或异常退出后
 * 会话会永久停在 MERGING。这里按 update_time 超时扫描，调用 UploadService 做幂等恢复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadMergingRecoveryJob {

    private static final int BATCH_SIZE = 200;

    private final UploadSessionMapper sessionMapper;
    private final UploadService uploadService;
    private final UploadProperties uploadProperties;

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1M")
    public void recoverStuckMerging() {
        LocalDateTime deadline = LocalDateTime.now().minus(uploadProperties.getMergingTimeout());
        List<UploadSession> stuck = sessionMapper.selectStuckMerging(deadline, BATCH_SIZE);
        for (UploadSession session : stuck) {
            try {
                uploadService.recoverMerging(session.getId());
            } catch (Exception e) {
                // 单个会话恢复失败不能中断整批；下一轮仍能重新捞到它。
                log.warn("恢复 MERGING 会话失败：uploadId={}", session.getId(), e);
            }
        }
    }
}
