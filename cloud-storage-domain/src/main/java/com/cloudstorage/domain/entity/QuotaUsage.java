package com.cloudstorage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户存储配额。used_bytes 是"事实源"（由 files 汇总而来），
 * Redis 里的计数只是快路径的预占，二者靠对账任务收敛。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("quota_usage")
public class QuotaUsage extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private Long quotaBytes;
    private Long usedBytes;
}
