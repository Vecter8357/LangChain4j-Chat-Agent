package com.melon.knowledge;

import java.time.Instant;
import java.util.List;

/** 知识平台 API 与持久化记录；引用始终携带原文和索引版本。 */
public final class KnowledgeModels {
    /** 保留用于组织知识库数据类型的静态命名空间。 */
    private KnowledgeModels() { }

    public record Base(String id, String name, String description, String status, Instant createdAt) { }
    public record Doc(String id, String baseId, String name, String purpose, String status,
                      int documentVersion, String activeIndexId, String pendingIndexId,
                      String error, Instant updatedAt) { }
    public record Version(String documentId, int version, String name, String bucket, String objectKey,
                          String contentType, long size, String sha256, Instant createdAt) { }
    public record Index(String id, String documentId, String baseId, int documentVersion,
                        String status, String chunkVersion, int chunkSize, int chunkOverlap,
                        String embeddingModel, String embeddingVersion, int dimension, String collectionName,
                        String error, Instant createdAt) { }
    public record Chunk(String id, String indexId, String documentId, String baseId,
                        int documentVersion, int ordinal, String content) { }
    public record Citation(String id, String baseId, String documentId, String documentName,
                           int documentVersion, String indexId, String chunkVersion,
                           String embeddingVersion, int ordinal, String text, String sourceUrl,
                           double denseScore, double lexicalScore, double fusionScore, double rerankScore) { }
    public record SearchRequest(String question, List<String> knowledgeBaseIds, List<String> fileIds, Integer limit) { }
    public record SearchResult(String retrievalMode, String reranker, List<Citation> citations, List<String> warnings) { }
}
