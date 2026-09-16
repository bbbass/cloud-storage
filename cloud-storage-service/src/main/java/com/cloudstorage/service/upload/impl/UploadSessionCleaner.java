package com.cloudstorage.service.upload.impl;

import com.cloudstorage.domain.entity.UploadSession;
import com.cloudstorage.domain.enums.UploadStatus;
import com.cloudstorage.domain.mapper.UploadPartMapper;
import com.cloudstorage.domain.mapper.UploadSessionMapper;
import com.cloudstorage.service.storage.StorageService;
import com.cloudstorage.service.quota.QuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 过期上传会话回收：断点续传的另一面 —— 客户端再也不回来的会话必须能被清理，
 * 否则 staging 里的分片对象会一直占空间。靠 upload_sessions.expire_time 驱动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadSessionCleaner {

    private static final int BATCH_SIZE = 200;

    private final UploadSessionMapper sessionMapper;
    private final UploadPartMapper partMapper;
    private final StorageService storageService;
    private final QuotaService quotaService;

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT10M")
    public void cleanupExpiredSessions() {
        List<UploadSession> expired = sessionMapper.selectExpired(BATCH_SIZE);
        if (expired.isEmpty()) {
            return;
        }
        for (UploadSession session : expired) {
            try {
                partMapper.selectPartNos(session.getId())
                        .forEach(partNo -> storageService.delete(UploadServiceImpl.stagingKeyOf(session.getId(), partNo)));
                partMapper.hardDeleteByUploadId(session.getId());
                sessionMapper.updateStatus(session.getId(), UploadStatus.EXPIRED.name());
                // 会话过期 = 用户放弃了这次上传，预占的配额要还回去
                quotaService.release(session.getUserId(), session.getFileSize());
                log.info("回收过期上传会话：uploadId={}, fileName={}", session.getId(), session.getFileName());
            } catch (Exception e) {
                log.warn("回收上传会话失败：uploadId={}", session.getId(), e);
            }
        }
    }
}
