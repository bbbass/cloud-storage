package com.cloudstorage.web.controller;

import com.cloudstorage.common.context.UserContext;
import com.cloudstorage.common.result.Result;
import com.cloudstorage.service.cache.FileMeta;
import com.cloudstorage.service.cache.FileMetaCache;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件元数据查询：走缓存（穿透/击穿/雪崩防护见 FileMetaCache）。
 * 下载、删除等写操作在 M4 补。
 */
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private final FileMetaCache fileMetaCache;

    @GetMapping("/{fileId}")
    public Result<FileMeta> meta(@PathVariable Long fileId) {
        return Result.ok(fileMetaCache.get(UserContext.userId(), fileId));
    }
}
