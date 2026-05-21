package com.rag.qa_system.service;

import com.rag.qa_system.mapper.DocumentChunkMapper;
import com.rag.qa_system.mapper.DocumentMapper;
import com.rag.qa_system.model.Document;
import com.rag.qa_system.model.DocumentChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class DocumentService {

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final PythonVectorClient pythonVectorClient;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "txt", "md");
    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 100;

    @Value("${app.upload-dir}")
    private String uploadDir;

    public DocumentService(DocumentMapper documentMapper,
                           DocumentChunkMapper documentChunkMapper,
                           PythonVectorClient pythonVectorClient) {
        this.documentMapper = documentMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.pythonVectorClient = pythonVectorClient;
    }

    public List<Document> listDocuments() {
        return documentMapper.findAll();
    }

    public Document getDocumentById(Long id) {
        return documentMapper.findById(id);
    }

    public Long createDocument(String name, String type, String filePath, Long fileSize) {
        Document document = new Document();
        document.setName(name);
        document.setType(type);
        document.setFilePath(filePath);
        document.setFileSize(fileSize);
        document.setStatus("processing");
        documentMapper.insert(document);
        return document.getId();
    }

    public Long uploadDocument(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("文件名非法");
        }

        String extension = getExtension(originalName).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("仅支持 PDF/TXT/MD 文件");
        }
        boolean needsVectorIndex = extension.equals("txt") || extension.equals("md");
        if (needsVectorIndex) {
            // 上传前先检查向量服务可用性，避免保存到一半才因连接失败中断
            pythonVectorClient.assertAvailable();
        }

        Path uploadPath = Path.of(uploadDir);
        Files.createDirectories(uploadPath);

        String storedName = UUID.randomUUID() + "_" + originalName;
        Path targetPath = uploadPath.resolve(storedName);
        Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

        Long documentId = createDocument(
                originalName,
                extension.toUpperCase(),
                targetPath.toString().replace("\\", "/"),
                file.getSize()
        );

        if (needsVectorIndex) {
            String content = Files.readString(targetPath, StandardCharsets.UTF_8);
            List<String> chunks = splitText(content, CHUNK_SIZE, CHUNK_OVERLAP);
            saveChunks(documentId, chunks);
            documentMapper.updateStatus(documentId, "completed");
        }

        return documentId;
    }

    private String getExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index < 0 || index == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(index + 1);
    }

    private List<String> splitText(String text, int chunkSize, int overlap) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap 必须小于 chunkSize");
        }

        int step = chunkSize - overlap;
        int start = 0;
        int length = text.length();
        while (start < length) {
            int end = Math.min(start + chunkSize, length);
            String chunk = text.substring(start, end).trim();
            if (!chunk.isBlank()) {
                result.add(chunk);
            }
            if (end == length) {
                break;
            }
            start += step;
        }
        return result;
    }

    private void saveChunks(Long documentId, List<String> chunks) {
        if (chunks.isEmpty()) {
            return;
        }

        List<DocumentChunk> entities = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocumentId(documentId);
            chunk.setChunkIndex(i);
            chunk.setContent(chunks.get(i));
            entities.add(chunk);
        }
        documentChunkMapper.batchInsert(entities);

        // 将分块同步到 Python FAISS 索引
        for (DocumentChunk chunk : entities) {
            pythonVectorClient.addChunk(chunk.getDocumentId(), chunk.getChunkIndex(), chunk.getContent());
        }
    }
}
