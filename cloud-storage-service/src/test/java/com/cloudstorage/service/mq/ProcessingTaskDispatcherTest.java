package com.cloudstorage.service.mq;

import com.cloudstorage.domain.entity.FileItem;
import com.cloudstorage.domain.entity.FileObject;
import com.cloudstorage.domain.entity.ProcessingTask;
import com.cloudstorage.domain.mapper.FileItemMapper;
import com.cloudstorage.domain.mapper.FileObjectMapper;
import com.cloudstorage.domain.mapper.ProcessingTaskMapper;
import com.cloudstorage.service.processor.ProcessingContext;
import com.cloudstorage.service.processor.ProcessingHandler;
import com.cloudstorage.service.processor.ProcessingHandlerRegistry;
import com.cloudstorage.service.processor.TaskTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 任务调度器单测：幂等（CAS 抢不到就不执行）、重试退避、重试耗尽进死信。
 */
@ExtendWith(MockitoExtension.class)
class ProcessingTaskDispatcherTest {

    @Mock
    private ProcessingTaskMapper taskMapper;
    @Mock
    private FileItemMapper fileItemMapper;
    @Mock
    private FileObjectMapper fileObjectMapper;
    @Mock
    private ProcessingHandlerRegistry registry;
    @Mock
    private MqProperties mqProperties;
    @Mock
    private ObjectProvider<MessagePublisher> publisherProvider;
    @Mock
    private MessagePublisher messagePublisher;
    @Mock
    private ProcessingHandler handler;

    private ProcessingTaskDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new ProcessingTaskDispatcher(taskMapper, fileItemMapper, fileObjectMapper,
                registry, mqProperties, publisherProvider);
    }

    @Test
    @DisplayName("CAS 抢不到执行权时直接跳过（消息重复投递也不会重复执行）")
    void skipsWhenCasFails() {
        when(taskMapper.selectById(1L)).thenReturn(task(1L, 0, "PENDING"));
        when(taskMapper.casMarkRunning(1L)).thenReturn(0);

        dispatcher.dispatch(1L);

        verifyNoInteractions(registry);
        verify(taskMapper, never()).markDone(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("失败且未超上限：回到 PENDING 并投递延迟重试消息")
    void retriesWithBackoff() {
        when(taskMapper.selectById(2L)).thenReturn(task(2L, 0, "PENDING"));
        when(taskMapper.casMarkRunning(2L)).thenReturn(1);
        when(fileItemMapper.selectById(any())).thenReturn(fileItem());
        when(fileObjectMapper.selectById(any())).thenReturn(fileObject());
        when(registry.get(TaskTypes.THUMBNAIL)).thenReturn(handler);
        doThrow(new IllegalStateException("boom")).when(handler).handle(any(ProcessingContext.class));
        when(mqProperties.getMaxRetry()).thenReturn(3);
        when(mqProperties.getRetryBackoff()).thenReturn(Duration.ofSeconds(3));
        when(publisherProvider.getObject()).thenReturn(messagePublisher);

        dispatcher.dispatch(2L);

        verify(taskMapper).markRetry(eq(2L), any(), anyString());
        verify(messagePublisher).publish(any(ProcessingMessage.class), eq(1));
    }

    @Test
    @DisplayName("重试次数耗尽：标记死信 DEAD，不再投递")
    void marksDeadWhenRetriesExhausted() {
        when(taskMapper.selectById(3L)).thenReturn(task(3L, 3, "PENDING"));
        when(taskMapper.casMarkRunning(3L)).thenReturn(1);
        when(fileItemMapper.selectById(any())).thenReturn(fileItem());
        when(fileObjectMapper.selectById(any())).thenReturn(fileObject());
        when(registry.get(TaskTypes.THUMBNAIL)).thenReturn(handler);
        doThrow(new IllegalStateException("always fails")).when(handler).handle(any(ProcessingContext.class));
        when(mqProperties.getMaxRetry()).thenReturn(3);

        dispatcher.dispatch(3L);

        verify(taskMapper).markDead(eq(3L), anyString());
        verify(messagePublisher, never()).publish(any(), any(Integer.class));
    }

    private ProcessingTask task(Long id, Integer retryCount, String status) {
        ProcessingTask task = new ProcessingTask();
        task.setId(id);
        task.setFileId(100L + id);
        task.setObjectId(200L + id);
        task.setUserId(1L);
        task.setTaskType(TaskTypes.THUMBNAIL);
        task.setStatus(status);
        task.setRetryCount(retryCount);
        return task;
    }

    private FileItem fileItem() {
        FileItem item = new FileItem();
        item.setId(101L);
        item.setUserId(1L);
        item.setObjectId(201L);
        item.setFileName("photo.jpg");
        return item;
    }

    private FileObject fileObject() {
        FileObject object = new FileObject();
        object.setId(201L);
        object.setObjectKey("objects/ab/photo.jpg");
        object.setContentType("image/jpeg");
        object.setSize(1024L);
        return object;
    }
}
