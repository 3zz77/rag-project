package com.rag.qa_system.controller;

import com.rag.qa_system.model.ApiResponse;
import com.rag.qa_system.model.PageResult;
import com.rag.qa_system.model.Document;
import com.rag.qa_system.service.DocumentService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;

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
    public ApiResponse<PageResult<Document>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) int pageSize) {
        return ApiResponse.success(documentService.listDocuments(page, pageSize));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody @Validated CreateDocumentRequest request) {
        Long id = documentService.createDocument(
                request.name(),
                request.type(),
                request.filePath(),
                request.fileSize()
        );
        return ApiResponse.success(Map.of("id", id, "message", "文档元数据创建成功"));
    }

    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(@RequestPart("file") MultipartFile file) throws Exception {
        Long id = documentService.uploadDocument(file);
        return ApiResponse.success(Map.of("id", id, "message", "文件上传并入库成功"));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        documentService.deleteDocument(id);
        return ApiResponse.success(Map.of("id", id, "message", "文档及索引已删除"));
    }

    public record CreateDocumentRequest(
            @NotBlank(message = "name不能为空") String name,
            @NotBlank(message = "type不能为空") String type,
            @NotBlank(message = "filePath不能为空") String filePath,
            @NotNull(message = "fileSize不能为空") Long fileSize
    ) {}
}
