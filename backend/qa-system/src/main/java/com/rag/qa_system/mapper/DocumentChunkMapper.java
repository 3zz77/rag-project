package com.rag.qa_system.mapper;

import com.rag.qa_system.model.DocumentChunk;
import org.apache.ibatis.annotations.*;
import java.util.List;

/**
 * 文档分块Mapper接口
 */
@Mapper
public interface DocumentChunkMapper {

    /**
     * 根据文档ID查询所有分块
     */
    @Select("SELECT * FROM document_chunks WHERE document_id = #{documentId} ORDER BY chunk_index")
    List<DocumentChunk> findByDocumentId(@Param("documentId") Long documentId);

    /**
     * 根据文档ID和分块序号查询单个分块
     */
    @Select("SELECT * FROM document_chunks WHERE document_id = #{documentId} AND chunk_index = #{chunkIndex} LIMIT 1")
    DocumentChunk findByDocIdAndChunkIndex(@Param("documentId") Long documentId,
                                           @Param("chunkIndex") Integer chunkIndex);

    /**
     * 查询所有分块（用于简单检索）
     */
    @Select("SELECT * FROM document_chunks")
    List<DocumentChunk> findAll();

    /**
     * 批量插入分块（XML中实现）
     */
    int batchInsert(@Param("chunks") List<DocumentChunk> chunks);

    /**
     * 删除文档的所有分块
     */
    @Delete("DELETE FROM document_chunks WHERE document_id = #{documentId}")
    int deleteByDocumentId(Long documentId);

    /**
     * 统计文档的分块数量
     */
    @Select("SELECT COUNT(*) FROM document_chunks WHERE document_id = #{documentId}")
    int countByDocumentId(Long documentId);
}
