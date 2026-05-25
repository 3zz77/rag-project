package com.rag.qa_system.controller;

import com.rag.qa_system.model.Document;
import com.rag.qa_system.service.DocumentService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
@Validated
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping
    public List<Document> list() {
        return documentService.listDocuments();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody @Validated CreateDocumentRequest request) {
        Long id = documentService.createDocument(
                request.name(),
                request.type(),
                request.filePath(),
                request.fileSize()
        );
        return Map.of("id", id, "message", "文档元数据创建成功");
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestPart("file") MultipartFile file) throws Exception {
        Long id = documentService.uploadDocument(file);
        return Map.of("id", id, "message", "文件上传并入库成功");
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        documentService.deleteDocument(id);
        return Map.of("id", id, "message", "文档及索引已删除");
    }

    public record CreateDocumentRequest(
            @NotBlank(message = "name不能为空") String name,
            @NotBlank(message = "type不能为空") String type,
            @NotBlank(message = "filePath不能为空") String filePath,
            @NotNull(message = "fileSize不能为空") Long fileSize
    ) {}
}
