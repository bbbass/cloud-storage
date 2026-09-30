package com.cloudstorage.service.file;

/**
 * 用户文件写操作。当前只提供删除，用来闭合引用计数与物理对象回收链路。
 */
public interface FileService {

    /**
     * 删除用户文件条目；引用计数归零后才删除物理对象和对象存储数据。
     * 删除动作幂等：重复删除同一 fileId 返回 30001。
     */
    void delete(Long userId, Long fileId);
}
