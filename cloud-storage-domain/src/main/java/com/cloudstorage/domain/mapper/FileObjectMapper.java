package com.cloudstorage.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudstorage.domain.entity.FileObject;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 物理对象 Mapper。引用计数的增减必须走原子 SQL，不能"先查后改"。
 */
public interface FileObjectMapper extends BaseMapper<FileObject> {

    @Update("UPDATE file_objects SET ref_count = ref_count + 1, update_time = NOW(3) WHERE id = #{id}")
    int incrementRef(@Param("id") Long id);

    @Update("UPDATE file_objects SET ref_count = ref_count - 1, update_time = NOW(3) "
            + "WHERE id = #{id} AND ref_count > 0")
    int decrementRef(@Param("id") Long id);

    /** 引用归零后的物理删除（本表不做逻辑删除） */
    @Delete("DELETE FROM file_objects WHERE id = #{id} AND ref_count <= 0")
    int hardDeleteIfUnreferenced(@Param("id") Long id);
}
