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
 * 上传会话资源回收：过期的 INIT/UPLOADING 和收尾不完整的 FAILED 都必须能被清理，
 * 否则 staging 分片和配额预占会一直留着。
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
        List<UploadSession> candidates = sessionMapper.selectCleanupCandidates(BATCH_SIZE);
        if (candidates.isEmpty()) {
            return;
        }
        for (UploadSession session : candidates) {
            try {
                boolean failed = UploadStatus.FAILED.name().equals(session.getStatus());
                // 先释放配额再删分片：Redis 失败时 upload_parts 还在，下一轮还能捞到并重试。
                quotaService.releaseOnce(session.getId(), session.getUserId(), session.getFileSize());
                partMapper.selectPartNos(session.getId())
                        .forEach(partNo -> storageService.delete(UploadServiceImpl.stagingKeyOf(session.getId(), partNo)));
                partMapper.hardDeleteByUploadId(session.getId());
                if (!failed) {
                    // FAILED 保留状态用于排查；清完分片后它不再出现在候选查询里。
                    sessionMapper.updateStatus(session.getId(), UploadStatus.EXPIRED.name());
                }
                log.info("回收上传会话：uploadId={}, status={}, fileName={}",
                        session.getId(), session.getStatus(), session.getFileName());
            } catch (Exception e) {
                log.warn("回收上传会话失败：uploadId={}", session.getId(), e);
            }
        }
    }
}
