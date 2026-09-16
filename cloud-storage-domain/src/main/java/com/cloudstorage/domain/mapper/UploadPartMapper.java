package com.cloudstorage.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudstorage.domain.entity.UploadPart;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface UploadPartMapper extends BaseMapper<UploadPart> {

    /** 已上传的分片序号，断点续传时回给客户端 */
    @Select("SELECT part_no FROM upload_parts WHERE upload_id = #{uploadId} AND deleted = 0 ORDER BY part_no")
    List<Integer> selectPartNos(@Param("uploadId") Long uploadId);

    @Select("SELECT COALESCE(SUM(part_size), 0) FROM upload_parts WHERE upload_id = #{uploadId} AND deleted = 0")
    long sumPartSize(@Param("uploadId") Long uploadId);

    @Delete("DELETE FROM upload_parts WHERE upload_id = #{uploadId}")
    int hardDeleteByUploadId(@Param("uploadId") Long uploadId);
}
