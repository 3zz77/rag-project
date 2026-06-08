package com.rag.qa_system.mapper;

import com.rag.qa_system.model.QaHistory;
import org.apache.ibatis.annotations.*;
import java.util.List;

/**
 * 问答历史Mapper接口
 */
@Mapper
public interface QaHistoryMapper {

    /**
     * 分页查询最近的历史记录
     */
    @Select("SELECT * FROM qa_history ORDER BY create_time DESC LIMIT #{offset}, #{limit}")
    List<QaHistory> findRecentWithPage(@Param("offset") int offset, @Param("limit") int limit);

    /**
     * 根据ID查询
     */
    @Select("SELECT * FROM qa_history WHERE id = #{id}")
    QaHistory findById(@Param("id") Long id);

    /**
     * 插入历史记录
     */
    @Insert("INSERT INTO qa_history(question, answer, context, model, conversation_id) " +
            "VALUES(#{question}, #{answer}, #{context}, #{model}, #{conversationId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(QaHistory history);

    /**
     * 删除历史记录
     */
    @Delete("DELETE FROM qa_history WHERE id = #{id}")
    int deleteById(Long id);

    /**
     * 统计历史记录数
     */
    @Select("SELECT COUNT(*) FROM qa_history")
    long count();
}
