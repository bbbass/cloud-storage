package com.cloudstorage.service.file;

import com.cloudstorage.common.exception.BusinessException;
import com.cloudstorage.common.result.ErrorCode;
import com.cloudstorage.domain.entity.FileItem;
import com.cloudstorage.domain.entity.FileObject;
import com.cloudstorage.domain.mapper.FileItemMapper;
import com.cloudstorage.domain.mapper.FileObjectMapper;
import com.cloudstorage.service.cache.FileMetaCache;
import com.cloudstorage.service.file.impl.FileServiceImpl;
import com.cloudstorage.service.quota.QuotaService;
import com.cloudstorage.service.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileServiceImplTest {

    @Mock
    private FileItemMapper fileItemMapper;
    @Mock
    private FileObjectMapper fileObjectMapper;
    @Mock
    private StorageService storageService;
    @Mock
    private FileMetaCache fileMetaCache;
    @Mock
    private QuotaService quotaService;
    @Mock
    private TransactionTemplate transactionTemplate;

    private FileServiceImpl fileService;

    @BeforeEach
    void setUp() {
        fileService = new FileServiceImpl(fileItemMapper, fileObjectMapper, storageService, fileMetaCache,
                quotaService, transactionTemplate);
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    @DisplayName("最后一个引用删除时减引用、删物理对象并回退配额")
    void deleteLastReferenceRemovesObjectAndRefundsQuota() {
        FileItem item = fileItem();
        FileObject object = fileObject();
        when(fileItemMapper.selectById(10L)).thenReturn(item);
        when(fileObjectMapper.selectById(20L)).thenReturn(object);
        when(fileItemMapper.deleteById(10L)).thenReturn(1);
        when(fileObjectMapper.decrementRef(20L)).thenReturn(1);
        when(fileObjectMapper.hardDeleteIfUnreferenced(20L)).thenReturn(1);

        fileService.delete(1L, 10L);

        verify(fileItemMapper).deleteById(10L);
        verify(fileObjectMapper).decrementRef(20L);
        verify(fileObjectMapper).hardDeleteIfUnreferenced(20L);
        verify(storageService).delete("objects/aa/object-key");
        verify(fileMetaCache).evict(10L);
        verify(quotaService).refund(10L, 1L, 1024L);
    }

    @Test
    @DisplayName("物理对象仍被其它文件引用时只减引用，不删对象存储")
    void deleteSharedObjectKeepsPhysicalObject() {
        FileItem item = fileItem();
        FileObject object = fileObject();
        when(fileItemMapper.selectById(10L)).thenReturn(item);
        when(fileObjectMapper.selectById(20L)).thenReturn(object);
        when(fileItemMapper.deleteById(10L)).thenReturn(1);
        when(fileObjectMapper.decrementRef(20L)).thenReturn(1);
        when(fileObjectMapper.hardDeleteIfUnreferenced(20L)).thenReturn(0);

        fileService.delete(1L, 10L);

        verify(storageService, never()).delete(any());
        verify(fileMetaCache).evict(10L);
        verify(quotaService).refund(10L, 1L, 1024L);
    }

    @Test
    @DisplayName("删除别人的文件返回 FORBIDDEN，不产生任何写操作")
    void rejectsDeletingOthersFile() {
        FileItem item = fileItem();
        item.setUserId(2L);
        when(fileItemMapper.selectById(10L)).thenReturn(item);

        assertThatThrownBy(() -> fileService.delete(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());

        verify(fileItemMapper, never()).deleteById(any());
        verify(fileObjectMapper, never()).decrementRef(any());
        verify(quotaService, never()).refund(any(), any(), anyLong());
    }

    private FileItem fileItem() {
        FileItem item = new FileItem();
        item.setId(10L);
        item.setUserId(1L);
        item.setObjectId(20L);
        item.setSize(1024L);
        item.setFileName("photo.jpg");
        return item;
    }

    private FileObject fileObject() {
        FileObject object = new FileObject();
        object.setId(20L);
        object.setObjectKey("objects/aa/object-key");
        object.setRefCount(1);
        object.setStatus("READY");
        return object;
    }
}
