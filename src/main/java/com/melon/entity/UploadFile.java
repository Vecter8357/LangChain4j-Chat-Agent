package com.melon.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 上传文件实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadFile {
    /** 对话临时附件与永久知识库文档使用不同存储及生命周期。 */
    @Builder.Default
    private String purpose = "TEMPORARY_ATTACHMENT";
    /**
     * 文件ID
     */
    private String id;
    
    /**
     * 原始文件名
     */
    private String originalFileName;
    
    /**
     * 保存的文件名
     */
    private String storedFileName;
    
    /** 对象存储类型。 */
    private String storageProvider;

    /** MinIO Bucket。 */
    private String bucketName;

    /** MinIO Object Key。 */
    private String objectKey;
    
    /**
     * 文件大小（字节）
     */
    private long size;
    
    /**
     * 文件类型
     */
    private String fileType;
    
    /**
     * 上传时间
     */
    private LocalDateTime uploadTime;
    
    /**
     * 是否已经加载到RAG系统
     */
    private boolean loadedToRag;

    /** 当前临时附件向量所用的提供方、模型版本及 Collection 标识。 */
    private String embeddingProfile;

    /**
     * 当前文件在向量存储中的 Point ID。该列表随 Redis 元数据持久化，应用重启后仍能判断索引状态。
     */
    @Builder.Default
    private List<String> embeddingIds = new ArrayList<>();
}
