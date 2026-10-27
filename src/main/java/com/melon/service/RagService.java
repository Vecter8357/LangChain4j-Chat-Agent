package com.melon.service;

import com.melon.entity.UploadFile;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import com.melon.config.properties.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG服务类
 * 负责文档解析和知识库问答
 */
@Service
@Slf4j
public class RagService {

    private final ChatModel chatLanguageModel;
    private final FileStorageService fileStorageService;
    private final DoclingDocumentProcessor doclingDocumentProcessor;
    
    // 内存中存储文档的嵌入
    private final EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();
    
    // 嵌入模型
    private final EmbeddingModel embeddingModel;
    
    // 文档解析器映射表
    private final Map<String, DocumentParser> documentParsers = new HashMap<>();
    
    // 文档分割器
    private final DocumentSplitter documentSplitter;
    
    // 内容检索器
    private final ContentRetriever contentRetriever;
    
    // 记录文件ID到文档片段的映射
    private final Map<String, List<TextSegment>> fileSegmentsMap = new HashMap<>();

    @Autowired
    public RagService(ChatModel chatLanguageModel,
                      FileStorageService fileStorageService,
                      EmbeddingModel embeddingModel,
                      DoclingDocumentProcessor doclingDocumentProcessor,
                      RagProperties ragProperties) {
        this.chatLanguageModel = chatLanguageModel;
        this.fileStorageService = fileStorageService;
        this.doclingDocumentProcessor = doclingDocumentProcessor;
        
        // 初始化嵌入模型
        this.embeddingModel = embeddingModel;
        
        // 初始化文档解析器
        documentParsers.put("pdf", new ApachePdfBoxDocumentParser());
        documentParsers.put("txt", new TextDocumentParser());
        
        // 初始化文档分割器
        this.documentSplitter = DocumentSplitters.recursive(
                ragProperties.getSplitter().getMaxSegmentSize(),
                ragProperties.getSplitter().getMaxOverlapSize()
        );
        
        // 初始化内容检索器
        this.contentRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(ragProperties.getSplitter().getMaxResults())
                .minScore(ragProperties.getSplitter().getMinScore())
                .build();
        
        log.info("RAG服务初始化完成");
    }

    /**
     * 加载文件到RAG系统
     *
     * @param file 上传的文件
     * @return 加载结果描述
     */
    public String loadFileToRag(UploadFile file) {
        try {
            log.info("开始加载文件到RAG系统: {}", file.getOriginalFileName());
            
            String fileExtension = getFileExtension(file.getOriginalFileName()).toLowerCase();
            Document document = parseDocument(file, fileExtension);
            
            // 分割文档
            List<TextSegment> segments = documentSplitter.split(document);
            enrichSegmentsMetadata(segments, file);
            log.info("文件 {} 被分割为 {} 个片段", file.getOriginalFileName(), segments.size());
            
            // 为片段创建嵌入并存储
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            embeddingStore.addAll(embeddings, segments);
            
            // 记录文件ID到分段的映射
            fileSegmentsMap.put(file.getId(), segments);
            
            // 更新文件状态
            file.setLoadedToRag(true);
            
            log.info("文件已成功加载到RAG系统: {}", file.getOriginalFileName());
            return "文件已成功加载到RAG系统，共 " + segments.size() + " 个文本片段";
        } catch (IOException e) {
            log.error("加载文件到RAG系统失败: {}", file.getOriginalFileName(), e);
            return "加载文件失败: " + e.getMessage();
        }
    }

    /**
     * 从RAG系统中移除文件
     *
     * @param fileId 文件ID
     */
    public void removeFileFromRag(String fileId) {
        List<TextSegment> segments = fileSegmentsMap.get(fileId);
        if (segments != null) {
            // 从嵌入存储中移除所有相关分段
            for (TextSegment segment : segments) {
                embeddingStore.remove(segment.text());
            }
            fileSegmentsMap.remove(fileId);
            log.info("已从RAG系统中移除文件: {}", fileId);
        }
    }

    /**
     * 基于RAG回答问题
     *
     * @param question 问题
     * @param fileIds  要查询的文件ID列表
     * @return 回答
     */
    public String answerWithRag(String question, List<String> fileIds) {
        try {
            log.info("基于RAG回答问题: {}, 文件数量: {}", question, fileIds.size());
            
            // 先加载未加载过的文件
            List<UploadFile> files = fileStorageService.getByIds(fileIds);
            for (UploadFile file : files) {
                if (!file.isLoadedToRag()) {
                    loadFileToRag(file);
                }
            }
            
            // 创建问答助手
            RagChatService chatService = AiServices.builder(RagChatService.class)
                    .chatModel(chatLanguageModel)
                    .contentRetriever(contentRetriever)
                    .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                    .build();
            
            // 获取回答
            String answer = chatService.chat(question);
            log.info("RAG回答: {}", answer);
            return answer;
        } catch (Exception e) {
            log.error("RAG回答问题失败", e);
            return "回答问题失败: " + e.getMessage();
        }
    }

    /**
     * RAG聊天服务接口
     */
    interface RagChatService {
        String chat(String message);
    }

    /**
     * 获取文件扩展名
     *
     * @param fileName 文件名
     * @return 扩展名
     */
    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf(".") + 1);
    }

    private Document parseDocument(UploadFile file, String fileExtension) throws IOException {
        return doclingDocumentProcessor.convertToDocument(file, fileExtension)
                .orElseGet(() -> {
                    if (!doclingDocumentProcessor.shouldFallbackToNativeParser()) {
                        throw new IllegalStateException("Docling未能解析文件，且已关闭原生解析回退: " + file.getOriginalFileName());
                    }
                    try {
                        return parseWithNativeParser(file, fileExtension);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
    }

    private Document parseWithNativeParser(UploadFile file, String fileExtension) throws IOException {
        DocumentParser parser = documentParsers.get(fileExtension);

        if (parser == null) {
            throw new IllegalStateException("不支持的文件类型: " + fileExtension
                    + "。请安装并启用Docling，或上传支持的类型: " + String.join(", ", documentParsers.keySet()));
        }

        Document document = parser.parse(Files.newInputStream(Paths.get(file.getFilePath())));
        document.metadata()
                .put(Document.FILE_NAME, file.getOriginalFileName())
                .put("source", "native")
                .put("file_id", file.getId());
        return document;
    }

    private void enrichSegmentsMetadata(List<TextSegment> segments, UploadFile file) {
        for (int i = 0; i < segments.size(); i++) {
            Metadata metadata = segments.get(i).metadata();
            metadata.put("file_id", file.getId());
            metadata.put("file_name", file.getOriginalFileName());
            metadata.put("chunk_index", i);
        }
    }
}
