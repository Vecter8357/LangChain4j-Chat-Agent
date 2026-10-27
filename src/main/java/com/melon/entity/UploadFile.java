package com.melon.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 上传文件实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadFile {
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
    
    /**
     * 文件路径
     */
    private String filePath;
    
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
} 