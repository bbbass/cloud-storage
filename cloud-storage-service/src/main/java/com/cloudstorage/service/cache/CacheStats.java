package com.cloudstorage.service.cache;

import lombok.Builder;
import lombok.Data;

/**
 * 缓存指标：命中率是 M6 要写进简历的数字来源。
 */
@Data
@Builder
public class CacheStats {

    private long hits;
    private long misses;
    private long nullHits;
    private long rebuilds;
    private long fallbackReads;
    private double hitRate;
}
