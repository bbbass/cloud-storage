package com.cloudstorage.service.upload;

import com.cloudstorage.service.upload.dto.CompleteUploadResult;
import com.cloudstorage.service.upload.dto.InitUploadCommand;
import com.cloudstorage.service.upload.dto.InitUploadResult;
import com.cloudstorage.service.upload.dto.PartUploadResult;
import com.cloudstorage.service.upload.dto.UploadStatusView;

import java.io.InputStream;

/**
 * 分片上传内核：初始化（含秒传）/ 传分片 / 合并 / 中止 / 查询进度。
 * 所有方法都要求传入 userId 做归属校验，禁止只凭 uploadId 操作。
 */
public interface UploadService {

    InitUploadResult init(Long userId, String deviceId, InitUploadCommand command);

    PartUploadResult uploadPart(Long userId, Long uploadId, int partNo, InputStream in, long declaredSize, String contentType);

    CompleteUploadResult complete(Long userId, Long uploadId);

    void abort(Long userId, Long uploadId);

    UploadStatusView status(Long userId, Long uploadId);
}
