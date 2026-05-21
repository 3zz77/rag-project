package com.rag.qa_system.mapper;

import com.rag.qa_system.model.Document;
import org.apache.ibatis.annotations.*;
import java.util.List;

/**
 * 文档Mapper接口
 */
@Mapper
public interface DocumentMapper {

    /**
     * 查询所有文档
     */
    @Select("SELECT * FROM documents ORDER BY upload_time DESC")
    List<Document> findAll();

    /**
     * 根据ID查询文档
     */
    @Select("SELECT * FROM documents WHERE id = #{id}")
    Document findById(@Param("id") Long id);

    /**
     * 插入文档
     */
    @Insert("INSERT INTO documents(name, type, file_path, file_size, status) " +
            "VALUES(#{name}, #{type}, #{filePath}, #{fileSize}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Document document);

    /**
     * 更新文档状态
     */
    @Update("UPDATE documents SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /**
     * 删除文档
     */
    @Delete("DELETE FROM documents WHERE id = #{id}")
    int deleteById(Long id);

    /**
     * 统计文档数量
     */
    @Select("SELECT COUNT(*) FROM documents")
    int count();
}
