package com.cloudstorage.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudstorage.domain.entity.ProcessingTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务表 Mapper。所有状态流转都用条件更新（CAS），保证"同一任务只被真正执行一次"。
 */
public interface ProcessingTaskMapper extends BaseMapper<ProcessingTask> {

    /** PENDING → RUNNING；返回 1 表示抢到执行权，0 表示别人已在跑或已完成（幂等短路） */
    @Update("UPDATE processing_tasks SET status = 'RUNNING', update_time = NOW(3) "
            + "WHERE id = #{id} AND status = 'PENDING' AND deleted = 0")
    int casMarkRunning(@Param("id") Long id);

    @Update("UPDATE processing_tasks SET status = 'DONE', result = #{result}, cost_ms = #{costMs}, "
            + "error_msg = NULL, update_time = NOW(3) WHERE id = #{id} AND status = 'RUNNING' AND deleted = 0")
    int markDone(@Param("id") Long id, @Param("result") String result, @Param("costMs") Long costMs);

    /** 未耗尽重试次数：回到 PENDING 并推迟到 next_retry_time */
    @Update("UPDATE processing_tasks SET status = 'PENDING', retry_count = retry_count + 1, "
            + "next_retry_time = #{nextRetryTime}, error_msg = #{errorMsg}, update_time = NOW(3) "
            + "WHERE id = #{id} AND status = 'RUNNING' AND deleted = 0")
    int markRetry(@Param("id") Long id, @Param("nextRetryTime") LocalDateTime nextRetryTime,
                  @Param("errorMsg") String errorMsg);

    /** 重试耗尽 → 死信 */
    @Update("UPDATE processing_tasks SET status = 'DEAD', retry_count = retry_count + 1, "
            + "error_msg = #{errorMsg}, update_time = NOW(3) WHERE id = #{id} AND status = 'RUNNING' AND deleted = 0")
    int markDead(@Param("id") Long id, @Param("errorMsg") String errorMsg);

    /** 死信重放：DEAD/FAILED → PENDING，重试次数清零 */
    @Update("UPDATE processing_tasks SET status = 'PENDING', retry_count = 0, next_retry_time = NULL, "
            + "error_msg = NULL, update_time = NOW(3) WHERE id = #{id} AND status = 'DEAD' AND deleted = 0")
    int casReplay(@Param("id") Long id);

    /** 补偿任务扫描：到点的 PENDING（含退避重试） */
    @Select("SELECT * FROM processing_tasks WHERE status = 'PENDING' "
            + "AND (next_retry_time IS NULL OR next_retry_time <= NOW(3)) AND deleted = 0 "
            + "ORDER BY id LIMIT #{limit}")
    List<ProcessingTask> selectDispatchable(@Param("limit") int limit);

    /** 长时间停留在 RUNNING 的任务（进程崩溃遗留），由补偿任务捞回 */
    @Select("SELECT * FROM processing_tasks WHERE status = 'RUNNING' "
            + "AND update_time < #{before} AND deleted = 0 ORDER BY id LIMIT #{limit}")
    List<ProcessingTask> selectStuckRunning(@Param("before") LocalDateTime before, @Param("limit") int limit);

    @Update("UPDATE processing_tasks SET status = 'PENDING', next_retry_time = NULL, update_time = NOW(3) "
            + "WHERE id = #{id} AND status = 'RUNNING' AND deleted = 0")
    int resetStuckRunning(@Param("id") Long id);

    @Select("SELECT status, COUNT(*) AS cnt FROM processing_tasks WHERE deleted = 0 GROUP BY status")
    List<java.util.Map<String, Object>> countGroupByStatus();
}
