package com.cloudstorage.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudstorage.domain.dto.QuotaSnapshot;
import com.cloudstorage.domain.entity.UploadSession;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 上传会话 Mapper。状态流转用条件更新（CAS），保证并发下"只合并一次"。
 */
public interface UploadSessionMapper extends BaseMapper<UploadSession> {

    /** INIT/UPLOADING → MERGING，返回 1 表示抢到了合并权 */
    @Update("UPDATE upload_sessions SET status = 'MERGING', update_time = NOW(3) "
            + "WHERE id = #{id} AND user_id = #{userId} AND status IN ('INIT', 'UPLOADING') AND deleted = 0")
    int casMarkMerging(@Param("id") Long id, @Param("userId") Long userId);

    @Update("UPDATE upload_sessions SET status = 'DONE', object_id = #{objectId}, file_id = #{fileId}, update_time = NOW(3) "
            + "WHERE id = #{id} AND status = 'MERGING' AND deleted = 0")
    int markDone(@Param("id") Long id, @Param("objectId") Long objectId, @Param("fileId") Long fileId);

    @Update("UPDATE upload_sessions SET status = #{status}, update_time = NOW(3) WHERE id = #{id} AND deleted = 0")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /** 只允许从 MERGING 落到 FAILED，避免恢复/完成并发时覆盖已 DONE 的状态 */
    @Update("UPDATE upload_sessions SET status = 'FAILED', update_time = NOW(3) "
            + "WHERE id = #{id} AND status = 'MERGING' AND deleted = 0")
    int markFailedIfMerging(@Param("id") Long id);

    /**
     * 待回收会话：过期的 INIT/UPLOADING，以及还残留分片记录的 FAILED。
     * FAILED 只要 staging/upload_parts 清干净就不再反复捞取。
     */
    @Select("SELECT s.* FROM upload_sessions s WHERE s.deleted = 0 AND ("
            + "(s.status IN ('INIT', 'UPLOADING') AND s.expire_time < NOW(3)) "
            + "OR (s.status = 'FAILED' AND EXISTS ("
            + "  SELECT 1 FROM upload_parts p WHERE p.upload_id = s.id AND p.deleted = 0"
            + "))"
            + ") ORDER BY s.update_time ASC LIMIT #{limit}")
    List<UploadSession> selectCleanupCandidates(@Param("limit") int limit);

    /** 超过阈值仍在 MERGING 的会话，交给恢复任务处理 */
    @Select("SELECT * FROM upload_sessions WHERE status = 'MERGING' "
            + "AND update_time < #{deadline} AND deleted = 0 "
            + "ORDER BY update_time ASC LIMIT #{limit}")
    List<UploadSession> selectStuckMerging(@Param("deadline") LocalDateTime deadline,
                                           @Param("limit") int limit);

    /** 恢复/登记事务内锁住会话行，避免和正常 complete 并发登记两次 */
    @Select("SELECT * FROM upload_sessions WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    UploadSession selectByIdForUpdate(@Param("id") Long id);

    /** 最终对象不存在时，把卡死的 MERGING 条件回退到 UPLOADING */
    @Update("UPDATE upload_sessions SET status = 'UPLOADING', expire_time = #{expireTime}, "
            + "update_time = NOW(3) WHERE id = #{id} AND status = 'MERGING' "
            + "AND update_time < #{deadline} AND deleted = 0")
    int resetStuckMerging(@Param("id") Long id,
                          @Param("deadline") LocalDateTime deadline,
                          @Param("expireTime") LocalDateTime expireTime);

    /** 进行中会话的预占量（配额对账用：这些字节已在 Redis 预占，但还没落到 files） */
    @Select("SELECT user_id AS userId, COALESCE(SUM(file_size), 0) AS pendingBytes FROM upload_sessions "
            + "WHERE status IN ('INIT', 'UPLOADING') AND expire_time > NOW(3) AND deleted = 0 GROUP BY user_id")
    List<QuotaSnapshot> sumPendingByUser();

    /** 注意：MyBatis 用方法名作为语句 id，不能与上面的同名方法重载，否则后者会被忽略 */
    @Select("SELECT COALESCE(SUM(file_size), 0) FROM upload_sessions WHERE user_id = #{userId} "
            + "AND status IN ('INIT', 'UPLOADING') AND expire_time > NOW(3) AND deleted = 0")
    long sumPendingBytesByUser(@Param("userId") Long userId);
}
