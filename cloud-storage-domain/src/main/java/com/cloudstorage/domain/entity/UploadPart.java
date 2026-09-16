package com.cloudstorage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 分片明细：(upload_id, part_no) 唯一，重复上传同一分片是幂等的。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("upload_parts")
public class UploadPart extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private Long uploadId;
    private Integer partNo;
    private Long partSize;
    private String sha256;
    private String etag;
}
