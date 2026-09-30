package com.cloudstorage.service.upload;

import cn.hutool.crypto.digest.DigestUtil;
import com.cloudstorage.common.exception.BusinessException;
import com.cloudstorage.common.result.ErrorCode;
import com.cloudstorage.domain.entity.FileItem;
import com.cloudstorage.domain.entity.FileObject;
import com.cloudstorage.domain.entity.UploadSession;
import com.cloudstorage.domain.enums.UploadStatus;
import com.cloudstorage.domain.mapper.FileItemMapper;
import com.cloudstorage.domain.mapper.FileObjectMapper;
import com.cloudstorage.domain.mapper.UploadPartMapper;
import com.cloudstorage.domain.mapper.UploadSessionMapper;
import com.cloudstorage.service.storage.StorageService;
import com.cloudstorage.service.quota.QuotaService;
import com.cloudstorage.service.mq.ProcessingTaskService;
import com.cloudstorage.service.upload.dto.CompleteUploadResult;
import com.cloudstorage.service.upload.dto.InitUploadCommand;
import com.cloudstorage.service.upload.dto.InitUploadResult;
import com.cloudstorage.service.upload.impl.UploadServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上传内核的单元测试（Mock 掉 Mapper 与存储），重点覆盖秒传的引用计数与幂等语义。
 */
@ExtendWith(MockitoExtension.class)
class UploadServiceImplTest {

    private static final String SHA256 = "a".repeat(64);

    @Mock
    private UploadSessionMapper sessionMapper;
    @Mock
    private UploadPartMapper partMapper;
    @Mock
    private FileObjectMapper fileObjectMapper;
    @Mock
    private FileItemMapper fileItemMapper;
    @Mock
    private StorageService storageService;
    @Mock
    private UploadProgressBus progressBus;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private QuotaService quotaService;
    @Mock
    private ProcessingTaskService processingTaskService;

    private UploadServiceImpl uploadService;
    private UploadProperties uploadProperties;

    @BeforeEach
    void setUp() {
        uploadProperties = new UploadProperties();
        uploadService = new UploadServiceImpl(sessionMapper, partMapper, fileObjectMapper, fileItemMapper,
                storageService, uploadProperties, progressBus, transactionTemplate, quotaService, processingTaskService);
        // 让 TransactionTemplate 直接执行回调，等效于"事务内的代码"；
        // 用 lenient 是因为部分用例在进入事务前就抛异常了
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @Test
    @DisplayName("秒传命中：创建新文件条目，并且必须增加物理对象引用计数")
    void instantUploadIncrementsRefCount() {
        Long objectId = 1001L;
        FileObject existing = new FileObject();
        existing.setId(objectId);
        existing.setSha256(SHA256);
        existing.setSize(1024L);
        existing.setStatus("READY");
        when(fileObjectMapper.selectList(any())).thenReturn(List.of(existing));
        when(fileObjectMapper.incrementRef(objectId)).thenReturn(1);
        when(fileItemMapper.insert(any(FileItem.class))).thenAnswer(invocation -> {
            FileItem item = invocation.getArgument(0);
            item.setId(2002L);
            return 1;
        });

        InitUploadResult result = uploadService.init(1L, "device-1", initCommand(1024L, null));

        assertThat(result.isInstantUpload()).isTrue();
        assertThat(result.getFileId()).isEqualTo(2002L);
        assertThat(result.getObjectId()).isEqualTo(objectId);
        verify(fileObjectMapper).incrementRef(objectId);
        verify(fileItemMapper).insert(any(FileItem.class));
    }

    @Test
    @DisplayName("分片大小小于 5MiB 时直接拒绝（MinIO compose 的硬约束）")
    void rejectsChunkSizeBelowMinimum() {
        InitUploadCommand command = initCommand(10L * 1024 * 1024, 1024L * 1024);

        assertThatThrownBy(() -> uploadService.init(1L, "device-1", command))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("分片大小");
    }

    @Test
    @DisplayName("sha256 格式非法时拒绝初始化")
    void rejectsInvalidSha256() {
        InitUploadCommand command = InitUploadCommand.builder()
                .fileName("a.bin")
                .fileSize(1024L)
                .sha256("not-a-sha256")
                .build();

        assertThatThrownBy(() -> uploadService.init(1L, "device-1", command))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
    }

    @Test
    @DisplayName("会话已完成时 complete 幂等：直接返回已有结果，不再合并")
    void completeIsIdempotentWhenSessionDone() {
        UploadSession done = new UploadSession();
        done.setId(3003L);
        done.setUserId(1L);
        done.setStatus(UploadStatus.DONE.name());
        done.setFileId(4004L);
        done.setObjectId(5005L);
        done.setSha256(SHA256);
        done.setFileSize(1024L);
        done.setChunkTotal(1);
        when(sessionMapper.selectById(3003L)).thenReturn(done);

        CompleteUploadResult result = uploadService.complete(1L, 3003L);

        assertThat(result.getFileId()).isEqualTo(4004L);
        assertThat(result.getObjectId()).isEqualTo(5005L);
        verify(storageService, never()).compose(any(), any(), any());
        verify(sessionMapper, never()).casMarkMerging(anyLong(), anyLong());
    }

    @Test
    @DisplayName("已 EXPIRED 的会话重复 abort 是幂等空操作，不再释放配额")
    void abortExpiredSessionIsIdempotent() {
        UploadSession expired = new UploadSession();
        expired.setId(3003L);
        expired.setUserId(1L);
        expired.setStatus(UploadStatus.EXPIRED.name());
        expired.setFileSize(1024L);
        expired.setChunkTotal(1);
        when(sessionMapper.selectById(3003L)).thenReturn(expired);

        uploadService.abort(1L, 3003L);

        verify(quotaService, never()).releaseOnce(any(), any(), anyLong());
        verify(partMapper, never()).hardDeleteByUploadId(any());
        verify(sessionMapper, never()).updateStatus(any(), anyString());
    }

    @Test
    @DisplayName("回读校验失败：置 FAILED、释放配额并清理 staging")
    void hashMismatchReleasesQuotaAndCleansStaging() {
        UploadSession session = uploadingSession(3003L, 2, 2048L);
        when(sessionMapper.selectById(3003L)).thenReturn(session);
        when(partMapper.selectPartNos(3003L)).thenReturn(List.of(1, 2));
        when(partMapper.sumPartSize(3003L)).thenReturn(2048L);
        when(sessionMapper.casMarkMerging(3003L, 1L)).thenReturn(1);
        when(storageService.get(anyString()))
                .thenReturn(new ByteArrayInputStream("bad".getBytes(StandardCharsets.UTF_8)));
        when(sessionMapper.markFailedIfMerging(3003L)).thenReturn(1);

        assertThatThrownBy(() -> uploadService.complete(1L, 3003L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.UPLOAD_HASH_MISMATCH.getCode());

        verify(storageService).delete("objects/" + SHA256.substring(0, 2) + "/" + SHA256);
        verify(quotaService).releaseOnce(3003L, 1L, 2048L);
        verify(partMapper).hardDeleteByUploadId(3003L);
    }

    @Test
    @DisplayName("MERGING 超时且最终对象不存在：回退 UPLOADING")
    void recoverMergingResetsWhenFinalObjectMissing() {
        UploadSession session = uploadingSession(3003L, 2, 2048L);
        session.setStatus(UploadStatus.MERGING.name());
        when(sessionMapper.selectById(3003L)).thenReturn(session);
        when(storageService.exists(anyString())).thenReturn(false);
        when(sessionMapper.resetStuckMerging(anyLong(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);

        uploadService.recoverMerging(3003L);

        verify(sessionMapper).resetStuckMerging(anyLong(), any(LocalDateTime.class), any(LocalDateTime.class));
        verify(sessionMapper, never()).markDone(any(), any(), any());
        verify(storageService, never()).compose(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("MERGING 超时但最终对象已存在：校验后补登记并投递任务")
    void recoverMergingRegistersWhenFinalObjectExists() {
        String content = "cloud-storage-recovery";
        String sha = DigestUtil.sha256Hex(content.getBytes(StandardCharsets.UTF_8));
        UploadSession session = uploadingSession(3003L, 1, (long) content.length());
        session.setStatus(UploadStatus.MERGING.name());
        session.setSha256(sha);
        session.setContentType("image/jpeg");
        session.setFileName("recover.jpg");

        FileObject object = new FileObject();
        object.setId(5005L);
        object.setSha256(sha);
        object.setSize((long) content.length());
        object.setStatus("READY");
        object.setObjectKey("objects/" + sha.substring(0, 2) + "/" + sha);

        when(sessionMapper.selectById(3003L)).thenReturn(session);
        when(storageService.exists(anyString())).thenReturn(true);
        when(storageService.get(anyString()))
                .thenReturn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        when(fileObjectMapper.selectList(any())).thenReturn(List.of(object));
        when(fileObjectMapper.incrementRef(5005L)).thenReturn(1);
        when(fileItemMapper.insert(any(FileItem.class))).thenAnswer(invocation -> {
            FileItem item = invocation.getArgument(0);
            item.setId(4004L);
            return 1;
        });
        when(sessionMapper.selectByIdForUpdate(3003L)).thenReturn(session);
        when(partMapper.selectPartNos(3003L)).thenReturn(List.of(1));
        when(sessionMapper.markDone(3003L, 5005L, 4004L)).thenReturn(1);

        uploadService.recoverMerging(3003L);

        verify(fileObjectMapper).incrementRef(5005L);
        verify(quotaService).commit(1L, (long) content.length());
        verify(sessionMapper).markDone(3003L, 5005L, 4004L);
        verify(processingTaskService).enqueueForFile(1L, 4004L, 5005L, "image/jpeg");
    }

    private UploadSession uploadingSession(Long id, int chunkTotal, long fileSize) {
        UploadSession session = new UploadSession();
        session.setId(id);
        session.setUserId(1L);
        session.setStatus(UploadStatus.UPLOADING.name());
        session.setFileName("photo.jpg");
        session.setFileSize(fileSize);
        session.setChunkSize((int) Math.max(1, fileSize / chunkTotal));
        session.setChunkTotal(chunkTotal);
        session.setSha256(SHA256);
        session.setContentType("image/jpeg");
        session.setParentId(0L);
        session.setExpireTime(LocalDateTime.now().plusHours(1));
        return session;
    }

    private InitUploadCommand initCommand(long fileSize, Long chunkSize) {
        return InitUploadCommand.builder()
                .fileName("photo.jpg")
                .fileSize(fileSize)
                .sha256(SHA256)
                .chunkSize(chunkSize)
                .contentType("image/jpeg")
                .build();
    }
}
