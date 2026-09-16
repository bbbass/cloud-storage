package com.cloudstorage.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudstorage.domain.dto.QuotaSnapshot;
import com.cloudstorage.domain.entity.UploadSession;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    /** 到期待回收的会话（供清理任务使用） */
    @Select("SELECT * FROM upload_sessions WHERE status IN ('INIT', 'UPLOADING') "
            + "AND expire_time < NOW(3) AND deleted = 0 LIMIT #{limit}")
    List<UploadSession> selectExpired(@Param("limit") int limit);

    /** 进行中会话的预占量（配额对账用：这些字节已在 Redis 预占，但还没落到 files） */
    @Select("SELECT user_id AS userId, COALESCE(SUM(file_size), 0) AS pendingBytes FROM upload_sessions "
            + "WHERE status IN ('INIT', 'UPLOADING') AND expire_time > NOW(3) AND deleted = 0 GROUP BY user_id")
    List<QuotaSnapshot> sumPendingByUser();

    /** 注意：MyBatis 用方法名作为语句 id，不能与上面的同名方法重载，否则后者会被忽略 */
    @Select("SELECT COALESCE(SUM(file_size), 0) FROM upload_sessions WHERE user_id = #{userId} "
            + "AND status IN ('INIT', 'UPLOADING') AND expire_time > NOW(3) AND deleted = 0")
    long sumPendingBytesByUser(@Param("userId") Long userId);
}
