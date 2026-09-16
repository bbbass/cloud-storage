package com.cloudstorage.domain.dto;

import lombok.Data;

/**
 * 对账用的聚合快照：DB 中的已提交用量 + 进行中会话的预占量。
 */
@Data
public class QuotaSnapshot {

    private Long userId;
    private Long usedBytes;
    private Long pendingBytes;
}
