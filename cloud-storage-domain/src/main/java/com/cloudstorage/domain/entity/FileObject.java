package com.cloudstorage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 物理对象：内容寻址（sha256 + size）+ 引用计数。
 * 注意：本表不用逻辑删除（deleted 固定 0），引用归零时由回收逻辑物理删除。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("file_objects")
public class FileObject extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String bucket;
    private String objectKey;
    private String storageType;
    private String sha256;
    private Long size;
    private String contentType;
    private Integer refCount;
    private String status;
}
