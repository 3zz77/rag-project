package com.rag.qa_system.service;

import com.rag.qa_system.mapper.DocumentChunkMapper;
import com.rag.qa_system.mapper.DocumentMapper;
import com.rag.qa_system.model.Document;
import com.rag.qa_system.model.DocumentChunk;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
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

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final PythonVectorClient pythonVectorClient;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "txt", "md", "doc", "docx");
    private static final int CHUNK_SIZE = 1000;
    private static final int CHUNK_OVERLAP = 200;
    private static final int MIN_CHUNK_SIZE = 100;

    // File magic numbers
    private static final byte[] PDF_MAGIC = {0x25, 0x50, 0x44, 0x46}; // %PDF
    private static final byte[] DOC_MAGIC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0}; // OLE2 (.doc)
    private static final byte[] DOCX_MAGIC = {0x50, 0x4B, 0x03, 0x04}; // ZIP/OPC (.docx)

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

    public void deleteDocument(Long id) {
        Document doc = documentMapper.findById(id);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在: " + id);
        }
        pythonVectorClient.deleteByDocumentId(id);
        documentMapper.deleteById(id);
        try {
            Files.deleteIfExists(Path.of(doc.getFilePath()));
        } catch (IOException e) {
            log.warn("删除文档文件失败: {}", doc.getFilePath(), e);
        }
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

        byte[] header = new byte[4];
        try (InputStream is = file.getInputStream()) {
            int read = is.read(header);
            if (read < 4) {
                throw new IllegalArgumentException("无法读取文件头，文件可能已损坏");
            }
        }

        verifyFileType(extension, header);

        pythonVectorClient.assertAvailable();

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

        try {
            String content = extractText(targetPath, extension);

            // 文字型 PDF 提取失败时，尝试 OCR 识别扫描件
            if ((content == null || content.isBlank()) && extension.equalsIgnoreCase("pdf")) {
                log.info("PDFBox未提取到文字，尝试 OCR 识别: {}", targetPath);
                try {
                    content = pythonVectorClient.extractTextViaOcr(
                            targetPath.toString().replace("\\", "/"));
                    log.info("OCR 识别成功，提取 {} 字符", content.length());
                } catch (Exception ocrError) {
                    log.warn("OCR 识别也失败: {}", ocrError.getMessage());
                }
            }

            if (content == null || content.isBlank()) {
                documentMapper.updateStatus(documentId, "failed");
                throw new IllegalArgumentException(
                    "该文件无可提取的文本内容。PDF 可能是扫描件但 OCR 识别也失败，请确认文件包含可读文字。"
                );
            }

            List<String> chunks = splitText(content, CHUNK_SIZE, CHUNK_OVERLAP);
            if (chunks.isEmpty()) {
                documentMapper.updateStatus(documentId, "completed");
                return documentId;
            }

            List<DocumentChunk> entities = new ArrayList<>();
            List<PythonVectorClient.ChunkItem> chunkItems = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                DocumentChunk c = new DocumentChunk();
                c.setDocumentId(documentId);
                c.setChunkIndex(i);
                c.setContent(chunks.get(i));
                entities.add(c);
                chunkItems.add(new PythonVectorClient.ChunkItem(documentId, i, chunks.get(i)));
            }

            documentChunkMapper.batchInsert(entities);

            pythonVectorClient.batchAddChunks(chunkItems);

            documentMapper.updateStatus(documentId, "completed");
        } catch (Exception e) {
            documentMapper.updateStatus(documentId, "failed");
            documentChunkMapper.deleteByDocumentId(documentId);
            try {
                pythonVectorClient.deleteByDocumentId(documentId);
            } catch (Exception ex) {
                log.warn("清理向量索引(回滚)失败: documentId={}", documentId, ex);
            }
            throw new IllegalStateException("文档处理失败，已回滚: " + e.getMessage(), e);
        }

        return documentId;
    }

    private void verifyFileType(String extension, byte[] header) {
        boolean isPdfHeader = header.length >= 4
                && header[0] == PDF_MAGIC[0]
                && header[1] == PDF_MAGIC[1]
                && header[2] == PDF_MAGIC[2]
                && header[3] == PDF_MAGIC[3];

        if (extension.equals("pdf") && !isPdfHeader) {
            throw new IllegalArgumentException("文件扩展名为 .pdf 但内容不是有效的 PDF 文件");
        }
        if (!extension.equals("pdf") && isPdfHeader) {
            throw new IllegalArgumentException("文件内容为 PDF，但扩展名不匹配，请使用 .pdf 后缀");
        }
        // doc/docx: check magic numbers
        boolean isDocHeader = header.length >= 4
                && header[0] == DOC_MAGIC[0] && header[1] == DOC_MAGIC[1]
                && header[2] == DOC_MAGIC[2] && header[3] == DOC_MAGIC[3];
        boolean isDocxHeader = header.length >= 4
                && header[0] == DOCX_MAGIC[0] && header[1] == DOCX_MAGIC[1]
                && header[2] == DOCX_MAGIC[2] && header[3] == DOCX_MAGIC[3];

        if (extension.equals("doc") && !isDocHeader) {
            throw new IllegalArgumentException("文件扩展名为 .doc 但内容不是有效的 Word 文档");
        }
        if (extension.equals("docx") && !isDocxHeader) {
            throw new IllegalArgumentException("文件扩展名为 .docx 但内容不是有效的 Word 文档");
        }
        // txt/md: reject binary content
        if ((extension.equals("txt") || extension.equals("md")) && isBinaryHeader(header)) {
            throw new IllegalArgumentException("文本文件包含二进制数据，请确认文件格式");
        }
    }

    private boolean isBinaryHeader(byte[] header) {
        for (byte b : header) {
            if (b == 0) return true;
        }
        return false;
    }

    private String extractText(Path filePath, String extension) throws IOException {
        if (extension.equals("pdf")) {
            try (PDDocument pdDoc = Loader.loadPDF(filePath.toFile())) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                return stripper.getText(pdDoc);
            }
        }
        if (extension.equals("docx")) {
            try (org.apache.poi.xwpf.usermodel.XWPFDocument doc =
                         new org.apache.poi.xwpf.usermodel.XWPFDocument(
                                 java.nio.file.Files.newInputStream(filePath))) {
                org.apache.poi.xwpf.extractor.XWPFWordExtractor extractor =
                        new org.apache.poi.xwpf.extractor.XWPFWordExtractor(doc);
                return extractor.getText();
            }
        }
        if (extension.equals("doc")) {
            try (org.apache.poi.hwpf.HWPFDocument doc =
                         new org.apache.poi.hwpf.HWPFDocument(
                                 java.nio.file.Files.newInputStream(filePath))) {
                org.apache.poi.hwpf.extractor.WordExtractor extractor =
                        new org.apache.poi.hwpf.extractor.WordExtractor(doc);
                String[] paragraphs = extractor.getParagraphText();
                StringBuilder sb = new StringBuilder();
                for (String p : paragraphs) {
                    if (p != null && !p.trim().isEmpty()) {
                        sb.append(p.trim()).append("\n");
                    }
                }
                return sb.toString();
            }
        }
        return Files.readString(filePath, StandardCharsets.UTF_8);
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

        // Split by paragraphs first
        String[] paragraphs = text.split("\\n\\s*\\n");
        List<String> segments = new ArrayList<>();
        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            // Keep code blocks intact
            if (trimmed.contains("```")) {
                segments.add(trimmed);
            } else if (trimmed.length() > chunkSize) {
                // Split large paragraphs by sentence boundaries
                String[] sentences = trimmed.split("(?<=[。！？.!?])\\s*");
                StringBuilder buf = new StringBuilder();
                for (String sentence : sentences) {
                    String s = sentence.trim();
                    if (s.isEmpty()) {
                        continue;
                    }
                    if (buf.length() + s.length() > chunkSize && buf.length() >= MIN_CHUNK_SIZE) {
                        segments.add(buf.toString().trim());
                        buf.setLength(0);
                    }
                    if (buf.length() > 0) {
                        buf.append(" ");
                    }
                    buf.append(s);
                }
                if (buf.length() > 0) {
                    segments.add(buf.toString().trim());
                }
            } else {
                segments.add(trimmed);
            }
        }

        // Merge small segments with overlap
        StringBuilder current = new StringBuilder();
        for (String seg : segments) {
            if (current.length() + seg.length() > chunkSize && current.length() >= MIN_CHUNK_SIZE) {
                result.add(current.toString().trim());
                // Keep overlap: retain last `overlap` chars from previous chunk
                String prev = current.toString();
                int overlapStart = Math.max(0, prev.length() - overlap);
                current.setLength(0);
                current.append(prev.substring(overlapStart));
                if (current.length() > 0 && !seg.startsWith(" ")) {
                    current.append(" ");
                }
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(seg);
        }
        if (current.length() > 0) {
            result.add(current.toString().trim());
        }

        return result;
    }
}
