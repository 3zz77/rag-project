package com.rag.qa_system.model;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 文档实体类
 */
@Data
public class Document {
    private Long id;
    private String name;
    private String type;
    private String filePath;
    private Long fileSize;
    private LocalDateTime uploadTime;
    private String status;
}
