package com.cloudstorage.service.quota.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QuotaView {

    private Long userId;
    private long quotaBytes;
    /** 已用 = 已提交文件 + 进行中上传的预占 */
    private long usedBytes;
    private long availableBytes;
}
