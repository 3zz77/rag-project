package com.rag.qa_system.model;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 问答历史实体类
 */
@Data
public class QaHistory {
    private Long id;
    private String question;
    private String answer;
    private String context;
    private String model;
    private LocalDateTime createTime;
}
