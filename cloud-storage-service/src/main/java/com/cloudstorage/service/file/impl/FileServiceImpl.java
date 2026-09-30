package com.cloudstorage.service.file.impl;

import com.cloudstorage.common.exception.BusinessException;
import com.cloudstorage.common.result.ErrorCode;
import com.cloudstorage.domain.entity.FileItem;
import com.cloudstorage.domain.entity.FileObject;
import com.cloudstorage.domain.mapper.FileItemMapper;
import com.cloudstorage.domain.mapper.FileObjectMapper;
import com.cloudstorage.service.cache.FileMetaCache;
import com.cloudstorage.service.file.FileService;
import com.cloudstorage.service.quota.QuotaService;
import com.cloudstorage.service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 文件删除：files 逻辑删除 → file_objects 原子减引用 → 归零才物理删。
 *
 * 物理删除和 Redis 配额回退必须放在事务提交后：
 * 事务回滚时不能提前删对象/改 Redis；提交后失败则由对账和对象存储补偿兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private final FileItemMapper fileItemMapper;
    private final FileObjectMapper fileObjectMapper;
    private final StorageService storageService;
    private final FileMetaCache fileMetaCache;
    private final QuotaService quotaService;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void delete(Long userId, Long fileId) {
        transactionTemplate.executeWithoutResult(status -> {
            FileItem item = fileItemMapper.selectById(fileId);
            if (item == null) {
                throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
            }
            if (!item.getUserId().equals(userId)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "无权删除该文件");
            }

            FileObject object = fileObjectMapper.selectById(item.getObjectId());
            if (fileItemMapper.deleteById(fileId) != 1) {
                throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
            }

            if (object != null) {
                fileObjectMapper.decrementRef(object.getId());
                int deletedObjects = fileObjectMapper.hardDeleteIfUnreferenced(object.getId());
                if (deletedObjects == 1) {
                    // 物理对象已无人引用，提交后再删存储；失败由存储侧日志/后续补偿发现。
                    runAfterCommit(() -> storageService.delete(object.getObjectKey()));
                }
            }

            // DB 立即扣减已提交用量，Redis 在提交后幂等扣减。
            quotaService.refund(fileId, userId, item.getSize());
            // 文件元数据缓存必须与删除一起失效，否则短期内还能读到已删除文件。
            runAfterCommit(() -> fileMetaCache.evict(fileId));
        });
        log.info("文件已删除：userId={}, fileId={}", userId, fileId);
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        action.run();
                    } catch (Exception e) {
                        // 事务已经提交，不能在这里反向失败；缓存/对象清理失败留给补偿或 TTL 兜底。
                        log.warn("文件删除后的补偿动作失败：fileId 已提交删除", e);
                    }
                }
            });
            return;
        }
        action.run();
    }
}
