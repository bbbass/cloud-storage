package com.cloudstorage.service.upload.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.cloudstorage.common.exception.BusinessException;
import com.cloudstorage.common.result.ErrorCode;
import com.cloudstorage.domain.entity.FileItem;
import com.cloudstorage.domain.entity.FileObject;
import com.cloudstorage.domain.entity.UploadPart;
import com.cloudstorage.domain.entity.UploadSession;
import com.cloudstorage.domain.enums.UploadStatus;
import com.cloudstorage.domain.mapper.FileItemMapper;
import com.cloudstorage.domain.mapper.FileObjectMapper;
import com.cloudstorage.domain.mapper.UploadPartMapper;
import com.cloudstorage.domain.mapper.UploadSessionMapper;
import com.cloudstorage.service.storage.StorageService;
import com.cloudstorage.service.limiter.RateLimitScene;
import com.cloudstorage.service.limiter.annotation.RateLimit;
import com.cloudstorage.service.quota.QuotaService;
import com.cloudstorage.service.mq.ProcessingTaskService;
import com.cloudstorage.service.upload.UploadProgress;
import com.cloudstorage.service.upload.UploadProgressBus;
import com.cloudstorage.service.upload.UploadProperties;
import com.cloudstorage.service.upload.UploadService;
import com.cloudstorage.service.upload.dto.CompleteUploadResult;
import com.cloudstorage.service.upload.dto.InitUploadCommand;
import com.cloudstorage.service.upload.dto.InitUploadResult;
import com.cloudstorage.service.upload.dto.PartUploadResult;
import com.cloudstorage.service.upload.dto.UploadStatusView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 分片上传内核实现。三条不变量：
 * 1) 分片可重复上传：同一 (uploadId, partNo) 覆盖写，结果幂等；
 * 2) 合并只执行一次：状态 CAS（INIT/UPLOADING → MERGING）抢占，重试直接返回已有结果；
 * 3) 物理对象按 (sha256, size) 唯一 + 引用计数，秒传与多用户重复文件共用同一份数据。
 *
 * 合并期间**不持有数据库事务**（compose 是外部 IO，可能秒级），只有最后的登记动作走短事务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadServiceImpl implements UploadService {

    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    private static final String STATUS_READY = "READY";
    private static final String STAGING_PREFIX = "staging/";
    private static final String OBJECT_PREFIX = "objects/";

    private final UploadSessionMapper sessionMapper;
    private final UploadPartMapper partMapper;
    private final FileObjectMapper fileObjectMapper;
    private final FileItemMapper fileItemMapper;
    private final StorageService storageService;
    private final UploadProperties uploadProperties;
    private final UploadProgressBus progressBus;
    private final TransactionTemplate transactionTemplate;
    private final QuotaService quotaService;
    private final ProcessingTaskService processingTaskService;

    @Override
    @RateLimit(scene = RateLimitScene.UPLOAD_INIT)
    public InitUploadResult init(Long userId, String deviceId, InitUploadCommand command) {
        String fileName = command.getFileName() == null ? null : command.getFileName().trim();
        if (fileName == null || fileName.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "文件名不能为空");
        }
        Long fileSize = command.getFileSize();
        if (fileSize == null || fileSize <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "文件大小必须大于 0");
        }
        String sha256 = normalizeSha256(command.getSha256());
        int chunkSize = (int) resolveChunkSize(command.getChunkSize());
        int chunkTotal = (int) ((fileSize + chunkSize - 1) / chunkSize);
        if (chunkTotal > uploadProperties.getMaxChunkTotal()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "分片数量 " + chunkTotal + " 超过上限 " + uploadProperties.getMaxChunkTotal() + "，请增大分片大小");
        }
        long parentId = command.getParentId() == null ? 0L : command.getParentId();

        // ① 秒传：物理对象已在库里，直接生成用户文件条目，一个字节都不用传
        FileObject existing = findReadyObject(sha256, fileSize);
        if (existing != null) {
            FileObject reused = existing;
            // 秒传不传字节，但它同样产生一个文件条目，所以一样要占配额
            quotaService.reserve(userId, fileSize);
            FileItem item;
            try {
                item = transactionTemplate.execute(status -> {
                    // 关键：秒传也要加引用计数，否则多个文件条目指向同一份物理数据时，
                    // 删除其中一个会让计数归零，把仍被引用的对象删掉。
                    fileObjectMapper.incrementRef(reused.getId());
                    FileItem created = createFileItem(userId, reused, fileName, parentId, command.getContentType());
                    quotaService.commit(userId, fileSize);
                    return created;
                });
            } catch (RuntimeException e) {
                quotaService.release(userId, fileSize);
                throw e;
            }
            log.info("秒传命中：userId={}, sha256={}, size={}", userId, sha256, fileSize);
            return InitUploadResult.builder()
                    .instantUpload(true)
                    .fileId(item == null ? null : item.getId())
                    .objectId(existing.getId())
                    .chunkSize(chunkSize)
                    .chunkTotal(chunkTotal)
                    .uploadedParts(List.of())
                    .message("秒传成功：服务端已有相同内容")
                    .build();
        }

        // ② 断点续传：复用未过期且未完成的会话
        UploadSession session = findActiveSession(userId, sha256, fileSize);
        if (session == null) {
            // 预占配额：Redis 原子判断 + 累加，超限直接拒绝（并发下不超卖）。
            // 续传（复用已有会话）时不再预占，否则同一次上传会被重复计数。
            quotaService.reserve(userId, fileSize);
            session = new UploadSession();
            session.setUserId(userId);
            session.setDeviceId(deviceId);
            session.setFileName(fileName);
            session.setFileSize(fileSize);
            session.setChunkSize(chunkSize);
            session.setChunkTotal(chunkTotal);
            session.setSha256(sha256);
            session.setContentType(command.getContentType());
            session.setParentId(parentId);
            session.setStatus(UploadStatus.INIT.name());
            session.setExpireTime(LocalDateTime.now().plus(uploadProperties.getSessionTtl()));
            try {
                sessionMapper.insert(session);
            } catch (RuntimeException e) {
                quotaService.release(userId, fileSize);
                throw e;
            }
            log.info("创建上传会话：uploadId={}, fileName={}, size={}, chunkTotal={}",
                    session.getId(), fileName, fileSize, chunkTotal);
        } else {
            log.info("复用上传会话（断点续传）：uploadId={}", session.getId());
        }

        List<Integer> uploadedParts = partMapper.selectPartNos(session.getId());
        return InitUploadResult.builder()
                .instantUpload(false)
                .uploadId(session.getId())
                .chunkSize(session.getChunkSize())
                .chunkTotal(session.getChunkTotal())
                .uploadedParts(uploadedParts)
                .expireTime(session.getExpireTime())
                .message(uploadedParts.isEmpty() ? "可以开始上传" : "检测到未完成上传，请续传缺失分片")
                .build();
    }

    @Override
    @RateLimit(scene = RateLimitScene.UPLOAD_PART)
    public PartUploadResult uploadPart(Long userId, Long uploadId, int partNo, InputStream in,
                                       long declaredSize, String contentType) {
        UploadSession session = requireOwnedSession(userId, uploadId);
        if (UploadStatus.MERGING.name().equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.UPLOAD_MERGING);
        }
        if (!UploadStatus.isActive(session.getStatus())) {
            throw new BusinessException(ErrorCode.UPLOAD_STATUS_INVALID, "会话状态：" + session.getStatus());
        }
        if (partNo < 1 || partNo > session.getChunkTotal()) {
            throw new BusinessException(ErrorCode.UPLOAD_CHUNK_OUT_OF_RANGE,
                    "分片序号应在 1.." + session.getChunkTotal() + " 之间");
        }

        long expectedSize = expectedPartSize(session, partNo);
        Path tmp = null;
        long actualSize;
        String partSha256;
        try {
            tmp = Files.createTempFile("cs-part-" + uploadId + "-" + partNo + "-", ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream digestIn = new DigestInputStream(in, digest)) {
                Files.copy(digestIn, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            actualSize = Files.size(tmp);
            partSha256 = HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.STORAGE_ERROR, "暂存分片失败：" + e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "SHA-256 不可用");
        }

        try {
            if (actualSize != expectedSize) {
                throw new BusinessException(ErrorCode.PARAM_ERROR,
                        "第 " + partNo + " 片大小不符：期望 " + expectedSize + "，实际 " + actualSize);
            }
            if (declaredSize > 0 && declaredSize != actualSize) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "声明大小与实际不一致");
            }
            String stagingKey = stagingKey(uploadId, partNo);
            try (InputStream fileIn = Files.newInputStream(tmp)) {
                storageService.put(stagingKey, fileIn, actualSize, contentType);
            }
            upsertPart(session.getId(), partNo, actualSize, partSha256);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.STORAGE_ERROR, "读取暂存分片失败：" + e.getMessage());
        } finally {
            deleteQuietly(tmp);
        }

        if (UploadStatus.INIT.name().equals(session.getStatus())) {
            sessionMapper.updateStatus(uploadId, UploadStatus.UPLOADING.name());
        }
        int uploaded = partMapper.selectPartNos(uploadId).size();
        int percent = percent(uploaded, session.getChunkTotal());
        publish(uploadId, UploadStatus.UPLOADING.name(), uploaded, session.getChunkTotal(), percent,
                "分片 " + partNo + " 已上传");

        return PartUploadResult.builder()
                .uploadId(uploadId)
                .partNo(partNo)
                .partSize(actualSize)
                .sha256(partSha256)
                .uploadedParts(uploaded)
                .totalParts(session.getChunkTotal())
                .percent(percent)
                .build();
    }

    @Override
    @RateLimit(scene = RateLimitScene.UPLOAD_COMPLETE)
    public CompleteUploadResult complete(Long userId, Long uploadId) {
        long start = System.currentTimeMillis();
        UploadSession session = requireOwnedSession(userId, uploadId);

        if (UploadStatus.DONE.name().equals(session.getStatus())) {
            return doneResult(session, start, true);
        }
        if (UploadStatus.MERGING.name().equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.UPLOAD_MERGING);
        }
        if (!UploadStatus.isActive(session.getStatus())) {
            throw new BusinessException(ErrorCode.UPLOAD_STATUS_INVALID, "会话状态：" + session.getStatus());
        }

        List<Integer> uploadedParts = partMapper.selectPartNos(uploadId);
        if (uploadedParts.size() != session.getChunkTotal()) {
            throw new BusinessException(ErrorCode.UPLOAD_PART_MISSING,
                    "已上传 " + uploadedParts.size() + "/" + session.getChunkTotal() + " 个分片");
        }
        long sumSize = partMapper.sumPartSize(uploadId);
        if (sumSize != session.getFileSize()) {
            throw new BusinessException(ErrorCode.UPLOAD_PART_MISSING,
                    "分片总大小 " + sumSize + " 与声明 " + session.getFileSize() + " 不一致");
        }

        // 合并权抢占：并发 complete 或客户端重试时，只有一个请求真正去 compose
        if (sessionMapper.casMarkMerging(uploadId, userId) != 1) {
            UploadSession latest = requireOwnedSession(userId, uploadId);
            if (UploadStatus.DONE.name().equals(latest.getStatus())) {
                return doneResult(latest, start, true);
            }
            throw new BusinessException(ErrorCode.UPLOAD_MERGING);
        }

        String finalKey = objectKey(session.getSha256());
        List<String> sources = IntStream.rangeClosed(1, session.getChunkTotal())
                .mapToObj(no -> stagingKey(uploadId, no))
                .collect(Collectors.toList());

        storageService.compose(finalKey, sources, session.getContentType());

        if (uploadProperties.isVerifyAfterMerge()) {
            String actual = sha256OfObject(finalKey);
            if (!actual.equalsIgnoreCase(session.getSha256())) {
                storageService.delete(finalKey);
                sessionMapper.updateStatus(uploadId, UploadStatus.FAILED.name());
                throw new BusinessException(ErrorCode.UPLOAD_HASH_MISMATCH);
            }
        }

        CompleteUploadResult result = transactionTemplate.execute(status -> {
            FileObject object = findReadyObject(session.getSha256(), session.getFileSize());
            boolean deduped = object != null;
            if (object == null) {
                object = new FileObject();
                object.setBucket(null);
                object.setObjectKey(finalKey);
                object.setStorageType(storageService.type());
                object.setSha256(session.getSha256());
                object.setSize(session.getFileSize());
                object.setContentType(session.getContentType());
                object.setRefCount(0);
                object.setStatus(STATUS_READY);
                try {
                    fileObjectMapper.insert(object);
                } catch (DuplicateKeyException e) {
                    // 并发下另一个请求先登记了同一份内容，改为复用
                    object = findReadyObject(session.getSha256(), session.getFileSize());
                    deduped = true;
                }
            }
            if (object == null) {
                throw new BusinessException(ErrorCode.STORAGE_ERROR, "物理对象登记失败");
            }
            fileObjectMapper.incrementRef(object.getId());

            FileItem item = createFileItem(userId, object, session.getFileName(), session.getParentId(),
                    session.getContentType());
            // DB 是配额的事实源：预占在 init 时已经做过，这里只把用量落到 DB
            quotaService.commit(userId, session.getFileSize());
            sessionMapper.markDone(uploadId, object.getId(), item == null ? null : item.getId());

            // 合并完成后分片对象不再需要，顺手清掉（失败不影响主流程）
            sources.forEach(storageService::delete);
            partMapper.hardDeleteByUploadId(uploadId);
            progressBus.clear(uploadId);

            return CompleteUploadResult.builder()
                    .uploadId(uploadId)
                    .fileId(item == null ? null : item.getId())
                    .objectId(object.getId())
                    .sha256(session.getSha256())
                    .size(session.getFileSize())
                    .deduped(deduped)
                    .costMs(System.currentTimeMillis() - start)
                    .build();
        });

        publish(uploadId, UploadStatus.DONE.name(), session.getChunkTotal(), session.getChunkTotal(), 100, "合并完成");
        // 事务已提交，再登记并投递异步任务（本地消息表模式：先落库、后投递，投递失败由补偿任务兜底）
        if (result != null) {
            processingTaskService.enqueueForFile(userId, result.getFileId(), result.getObjectId(),
                    session.getContentType());
        }
        log.info("上传完成：uploadId={}, fileId={}, objectId={}, deduped={}, 耗时={}ms",
                uploadId, result == null ? null : result.getFileId(),
                result == null ? null : result.getObjectId(),
                result != null && result.isDeduped(), System.currentTimeMillis() - start);
        return result;
    }

    @Override
    public void abort(Long userId, Long uploadId) {
        UploadSession session = requireOwnedSession(userId, uploadId);
        if (UploadStatus.DONE.name().equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.UPLOAD_STATUS_INVALID, "已完成的上传不能中止");
        }
        partMapper.selectPartNos(uploadId).forEach(no -> storageService.delete(stagingKey(uploadId, no)));
        partMapper.hardDeleteByUploadId(uploadId);
        sessionMapper.updateStatus(uploadId, UploadStatus.EXPIRED.name());
        // 回滚预占，否则用户"传了又取消"会白白占着配额
        quotaService.release(userId, session.getFileSize());
        progressBus.publish(UploadProgress.builder()
                .uploadId(uploadId)
                .status(UploadStatus.EXPIRED.name())
                .uploadedParts(0)
                .totalParts(session.getChunkTotal())
                .percent(0)
                .message("上传已中止")
                .timestamp(System.currentTimeMillis())
                .build());
        log.info("上传已中止：uploadId={}", uploadId);
    }

    @Override
    public UploadStatusView status(Long userId, Long uploadId) {
        UploadSession session = requireOwnedSession(userId, uploadId);
        List<Integer> uploadedParts = partMapper.selectPartNos(uploadId);
        int percent = UploadStatus.DONE.name().equals(session.getStatus())
                ? 100
                : percent(uploadedParts.size(), session.getChunkTotal());
        return UploadStatusView.builder()
                .uploadId(uploadId)
                .status(session.getStatus())
                .fileName(session.getFileName())
                .fileSize(session.getFileSize())
                .chunkSize(session.getChunkSize())
                .totalParts(session.getChunkTotal())
                .uploadedParts(uploadedParts)
                .percent(percent)
                .fileId(session.getFileId())
                .objectId(session.getObjectId())
                .expireTime(session.getExpireTime())
                .build();
    }

    /* ------------------------------ 内部方法 ------------------------------ */

    private UploadSession requireOwnedSession(Long userId, Long uploadId) {
        UploadSession session = sessionMapper.selectById(uploadId);
        if (session == null) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        if (!session.getUserId().equals(userId)) {
            // 不暴露"会话属于别人"这一信息，统一按无权限处理
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该上传会话");
        }
        return session;
    }

    private FileObject findReadyObject(String sha256, Long size) {
        return fileObjectMapper.selectList(Wrappers.<FileObject>lambdaQuery()
                        .eq(FileObject::getSha256, sha256)
                        .eq(FileObject::getSize, size)
                        .eq(FileObject::getStatus, STATUS_READY)
                        .last("limit 1"))
                .stream().findFirst().orElse(null);
    }

    private UploadSession findActiveSession(Long userId, String sha256, Long fileSize) {
        return sessionMapper.selectList(Wrappers.<UploadSession>lambdaQuery()
                        .eq(UploadSession::getUserId, userId)
                        .eq(UploadSession::getSha256, sha256)
                        .eq(UploadSession::getFileSize, fileSize)
                        .in(UploadSession::getStatus, UploadStatus.INIT.name(), UploadStatus.UPLOADING.name())
                        .gt(UploadSession::getExpireTime, LocalDateTime.now())
                        .orderByDesc(UploadSession::getId)
                        .last("limit 1"))
                .stream().findFirst().orElse(null);
    }

    private FileItem createFileItem(Long userId, FileObject object, String fileName, Long parentId, String contentType) {
        FileItem item = new FileItem();
        item.setUserId(userId);
        item.setObjectId(object.getId());
        item.setFileName(fileName);
        item.setParentId(parentId == null ? 0L : parentId);
        item.setSize(object.getSize());
        item.setSha256(object.getSha256());
        item.setContentType(contentType);
        fileItemMapper.insert(item);
        return item;
    }

    private void upsertPart(Long uploadId, int partNo, long partSize, String sha256) {
        UploadPart existing = partMapper.selectOne(Wrappers.<UploadPart>lambdaQuery()
                .eq(UploadPart::getUploadId, uploadId)
                .eq(UploadPart::getPartNo, partNo)
                .last("limit 1"));
        if (existing == null) {
            UploadPart part = new UploadPart();
            part.setUploadId(uploadId);
            part.setPartNo(partNo);
            part.setPartSize(partSize);
            part.setSha256(sha256);
            part.setEtag(sha256);
            try {
                partMapper.insert(part);
                return;
            } catch (DuplicateKeyException e) {
                // 并发重传同一分片：落到下面的更新分支
                existing = partMapper.selectOne(Wrappers.<UploadPart>lambdaQuery()
                        .eq(UploadPart::getUploadId, uploadId)
                        .eq(UploadPart::getPartNo, partNo)
                        .last("limit 1"));
                if (existing == null) {
                    throw e;
                }
            }
        }
        existing.setPartSize(partSize);
        existing.setSha256(sha256);
        existing.setEtag(sha256);
        partMapper.updateById(existing);
    }

    private long resolveChunkSize(Long requested) {
        long chunkSize = requested == null ? uploadProperties.getDefaultChunkSize() : requested;
        if (chunkSize < uploadProperties.getMinChunkSize()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "分片大小不能小于 " + uploadProperties.getMinChunkSize() + " 字节（对象存储 compose 限制）");
        }
        if (chunkSize > uploadProperties.getMaxChunkSize()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "分片大小不能大于 " + uploadProperties.getMaxChunkSize() + " 字节");
        }
        return chunkSize;
    }

    private String normalizeSha256(String sha256) {
        if (sha256 == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "缺少文件 sha256");
        }
        String normalized = sha256.trim().toLowerCase();
        if (!SHA256_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "sha256 格式不正确（应为 64 位十六进制）");
        }
        return normalized;
    }

    private long expectedPartSize(UploadSession session, int partNo) {
        if (partNo < session.getChunkTotal()) {
            return session.getChunkSize();
        }
        long tail = session.getFileSize() - (long) session.getChunkSize() * (session.getChunkTotal() - 1);
        return tail <= 0 ? session.getChunkSize() : tail;
    }

    private String stagingKey(Long uploadId, int partNo) {
        return STAGING_PREFIX + uploadId + "/" + partNo;
    }

    private String objectKey(String sha256) {
        return OBJECT_PREFIX + sha256.substring(0, 2) + "/" + sha256;
    }

    private int percent(int uploaded, int total) {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.min(100L, Math.round(uploaded * 100.0 / total));
    }

    private String sha256OfObject(String objectKey) {
        try (InputStream in = storageService.get(objectKey)) {
            return DigestUtil.sha256Hex(in);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.STORAGE_ERROR, "校验合并对象失败：" + e.getMessage());
        }
    }

    private CompleteUploadResult doneResult(UploadSession session, long start, boolean deduped) {
        return CompleteUploadResult.builder()
                .uploadId(session.getId())
                .fileId(session.getFileId())
                .objectId(session.getObjectId())
                .sha256(session.getSha256())
                .size(session.getFileSize())
                .deduped(deduped)
                .costMs(System.currentTimeMillis() - start)
                .build();
    }

    private void publish(Long uploadId, String status, int uploaded, int total, int percent, String message) {
        progressBus.publish(UploadProgress.builder()
                .uploadId(uploadId)
                .status(status)
                .uploadedParts(uploaded)
                .totalParts(total)
                .percent(percent)
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build());
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除临时文件失败：{}", path, e);
        }
    }

    /** 供同包的清理任务复用分片 key 生成逻辑 */
    static String stagingKeyOf(Long uploadId, int partNo) {
        return STAGING_PREFIX + uploadId + "/" + partNo;
    }
}
