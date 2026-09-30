package com.cloudstorage.service.quota;

import com.cloudstorage.service.quota.dto.QuotaView;

/**
 * 存储配额。语义：
 * - reserve：上传开始前预占，Redis 原子累加，超限直接拒绝（并发下不超卖）；
 * - commit：上传完成时把用量落到 DB（事实源）；
 * - release：上传中止/过期时回滚预占；
 * - reconcile：对账，把 DB 与 Redis 收敛到一致（Redis 丢了或写坏了都能修回来）。
 */
public interface QuotaService {

    QuotaView getQuota(Long userId);

    void reserve(Long userId, long size);

    void commit(Long userId, long size);

    void release(Long userId, long size);

    /** 按 uploadId 幂等释放上传预占，重复调用不会重复扣 Redis */
    void releaseOnce(Long uploadId, Long userId, long size);

    /** 文件删除后回退已提交配额：DB 立即扣减，Redis 在事务提交后幂等扣减 */
    void refund(Long fileId, Long userId, long size);

    /** 全量对账，返回处理的用户数 */
    int reconcile();
}
