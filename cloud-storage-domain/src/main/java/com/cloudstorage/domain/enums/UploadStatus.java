package com.cloudstorage.domain.enums;

/**
 * 上传会话状态机：INIT → UPLOADING → MERGING → DONE，异常分支 FAILED / EXPIRED。
 * 状态流转只在 service.upload 里做，其他模块只读。
 */
public enum UploadStatus {

    /** 会话已创建，尚未收到分片 */
    INIT,
    /** 至少收到一个分片 */
    UPLOADING,
    /** 正在合并（CAS 抢占，保证只合一次） */
    MERGING,
    /** 已完成，file_id / object_id 有效 */
    DONE,
    /** 合并或校验失败 */
    FAILED,
    /** 超时未完成，被清理任务回收 */
    EXPIRED;

    public static boolean isActive(String status) {
        return INIT.name().equals(status) || UPLOADING.name().equals(status);
    }
}
