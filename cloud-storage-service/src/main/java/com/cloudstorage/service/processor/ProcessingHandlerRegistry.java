package com.cloudstorage.service.processor;

import com.cloudstorage.common.exception.BusinessException;
import com.cloudstorage.common.result.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 处理器注册表：SPI 的落点。启动时把所有 ProcessingHandler 按 taskType 建索引。
 */
@Slf4j
@Component
public class ProcessingHandlerRegistry {

    private final Map<String, ProcessingHandler> handlers;

    public ProcessingHandlerRegistry(List<ProcessingHandler> handlerList) {
        Map<String, ProcessingHandler> map = new LinkedHashMap<>();
        for (ProcessingHandler handler : handlerList) {
            map.put(handler.taskType(), handler);
        }
        this.handlers = Map.copyOf(map);
        log.info("已注册异步处理器：{}", handlers.keySet());
    }

    public ProcessingHandler get(String taskType) {
        ProcessingHandler handler = handlers.get(taskType);
        if (handler == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "未注册的处理器类型：" + taskType);
        }
        return handler;
    }
}
