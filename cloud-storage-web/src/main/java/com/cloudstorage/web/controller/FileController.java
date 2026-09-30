package com.cloudstorage.web.controller;

import com.cloudstorage.common.context.UserContext;
import com.cloudstorage.common.result.Result;
import com.cloudstorage.service.cache.FileMeta;
import com.cloudstorage.service.cache.FileMetaCache;
import com.cloudstorage.service.file.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件元数据查询：走缓存（穿透/击穿/雪崩防护见 FileMetaCache）。
 * 删除用于闭合引用计数；下载接口仍在 M4 补。
 */
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private final FileMetaCache fileMetaCache;
    private final FileService fileService;

    @GetMapping("/{fileId}")
    public Result<FileMeta> meta(@PathVariable Long fileId) {
        return Result.ok(fileMetaCache.get(UserContext.userId(), fileId));
    }

    /** 删除文件条目；引用计数归零后才删物理对象。 */
    @DeleteMapping("/{fileId}")
    public Result<Void> delete(@PathVariable Long fileId) {
        fileService.delete(UserContext.userId(), fileId);
        return Result.ok();
    }
}
