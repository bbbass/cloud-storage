package com.cloudstorage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户文件条目（表名 files）。多个条目可指向同一 FileObject —— 这就是秒传与去重的落点。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("files")
public class FileItem extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private Long objectId;
    private String fileName;
    private Long parentId;
    private Long size;
    private String sha256;
    private String contentType;
}
