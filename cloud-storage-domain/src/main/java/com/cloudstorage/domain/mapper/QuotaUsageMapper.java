package com.cloudstorage.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudstorage.domain.dto.QuotaSnapshot;
import com.cloudstorage.domain.entity.QuotaUsage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface QuotaUsageMapper extends BaseMapper<QuotaUsage> {

    /** 原子累加，避免"先查后写"在并发下丢更新 */
    @Update("UPDATE quota_usage SET used_bytes = used_bytes + #{size}, update_time = NOW(3) "
            + "WHERE user_id = #{userId} AND deleted = 0")
    int addUsed(@Param("userId") Long userId, @Param("size") long size);

    @Update("UPDATE quota_usage SET used_bytes = GREATEST(used_bytes - #{size}, 0), update_time = NOW(3) "
            + "WHERE user_id = #{userId} AND deleted = 0")
    int subtractUsed(@Param("userId") Long userId, @Param("size") long size);

    /** 以 files 为准，把每个用户的 used_bytes 重算一遍（对账的事实源） */
    @Update("UPDATE quota_usage q SET q.used_bytes = ("
            + "  SELECT COALESCE(SUM(f.size), 0) FROM files f WHERE f.user_id = q.user_id AND f.deleted = 0"
            + "), q.update_time = NOW(3) WHERE q.deleted = 0")
    int reconcileUsedBytesFromFiles();

    /** 每个用户 DB 中已提交的用量 */
    @Select("SELECT f.user_id AS userId, COALESCE(SUM(f.size), 0) AS usedBytes FROM files f "
            + "WHERE f.deleted = 0 GROUP BY f.user_id")
    List<QuotaSnapshot> sumCommittedByUser();

    @Select("SELECT user_id FROM quota_usage WHERE deleted = 0")
    List<Long> selectAllUserIds();
}
