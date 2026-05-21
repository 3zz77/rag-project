package com.rag.qa_system.model;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 文档分块实体类
 */
@Data
public class DocumentChunk {
    private Long id;
    private Long documentId;
    private Integer chunkIndex;
    private String content;
    private LocalDateTime createTime;
}
