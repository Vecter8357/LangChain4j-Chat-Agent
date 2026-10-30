package com.melon.service;

import com.melon.config.RedisCache;
import com.melon.entity.UploadFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 上传文件服务。文件内容保存到 MinIO，Redis 只保存文件元数据。
 */
@Slf4j
@Service
public class FileStorageService {

    private static final String STORAGE_PROVIDER = "MINIO";

    private final long maxFileSize;
    private final String allowedTypes;
    private final String metadataKeyPrefix;
    private final RedisCache redisCache;
    private final MinioObjectStorageService objectStorage;

    /** 创建上传文件服务。 */
    public FileStorageService(@Value("${file.upload.max-size}") long maxFileSize,
                              @Value("${file.upload.allowed-types}") String allowedTypes,
                              @Value("${file.upload.metadata-key-prefix:upload:file:}") String metadataKeyPrefix,
                              RedisCache redisCache,
                              MinioObjectStorageService objectStorage) {
        this.maxFileSize = maxFileSize;
        this.allowedTypes = allowedTypes;
        this.metadataKeyPrefix = metadataKeyPrefix;
        this.redisCache = redisCache;
        this.objectStorage = objectStorage;
    }

    /**
     * 校验并把一个上传文件写入 MinIO，然后保存元数据。
     */
    public UploadFile store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (file.getSize() > maxFileSize) {
            throw new IllegalArgumentException("文件太大，最大允许大小为: "
                    + (maxFileSize / 1024 / 1024) + "MB");
        }

        String fileId = UUID.randomUUID().toString();
        String originalFileName = safeOriginalFileName(file.getOriginalFilename());
        String fileExtension = getFileExtension(originalFileName);
        validateFileExtension(fileExtension);
        String storedFileName = fileId + "." + fileExtension.toLowerCase(Locale.ROOT);
        String objectKey = objectKey(storedFileName);
        String bucket = objectStorage.bucket();

        try (InputStream input = file.getInputStream()) {
            objectStorage.putObject(objectKey, input, file.getSize(), file.getContentType());
        } catch (IOException error) {
            throw new IllegalStateException("读取上传文件失败: " + originalFileName, error);
        }

        UploadFile uploadFile = UploadFile.builder()
                .id(fileId)
                .originalFileName(originalFileName)
                .storedFileName(storedFileName)
                .storageProvider(STORAGE_PROVIDER)
                .bucketName(bucket)
                .objectKey(objectKey)
                .size(file.getSize())
                .fileType(StringUtils.hasText(file.getContentType())
                        ? file.getContentType() : "application/octet-stream")
                .uploadTime(LocalDateTime.now())
                .loadedToRag(false)
                .build();

        try {
            redisCache.setCacheObject(metadataKey(fileId), uploadFile);
        } catch (RuntimeException error) {
            try {
                objectStorage.removeObject(bucket, objectKey);
            } catch (RuntimeException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw new IllegalStateException("文件已上传但元数据保存失败，已尝试回滚: " + originalFileName, error);
        }

        log.info("文件已上传到 MinIO: fileId={}, bucket={}, objectKey={}", fileId, bucket, objectKey);
        return uploadFile;
    }

    /** 存储多个非空文件。 */
    public List<UploadFile> storeAll(List<MultipartFile> files) {
        List<UploadFile> result = new ArrayList<>();
        if (files == null) {
            return result;
        }
        for (MultipartFile file : files) {
            if (file != null && !file.isEmpty()) {
                result.add(store(file));
            }
        }
        return result;
    }

    /** 按 ID 获取文件元数据。 */
    public UploadFile getById(String fileId) {
        UploadFile file = redisCache.getCacheObject(metadataKey(fileId));
        if (file == null) {
            throw new IllegalArgumentException("文件不存在，ID: " + fileId);
        }
        return file;
    }

    /** 按 ID 列表获取存在的文件元数据。 */
    public List<UploadFile> getByIds(List<String> fileIds) {
        List<UploadFile> result = new ArrayList<>();
        if (fileIds == null) {
            return result;
        }
        for (String fileId : fileIds) {
            UploadFile file = redisCache.getCacheObject(metadataKey(fileId));
            if (file != null) {
                result.add(file);
            }
        }
        return result;
    }

    /**
     * 将 MinIO 对象临时下载到本地，调用方必须使用 try-with-resources 关闭结果。
     */
    public MinioObjectStorageService.MaterializedObject materialize(UploadFile file) throws IOException {
        requireMinioFile(file);
        return objectStorage.materialize(file.getBucketName(), file.getObjectKey(),
                getFileExtension(file.getOriginalFileName()));
    }

    /** 打开文件对象流，调用方负责关闭。 */
    public InputStream openStream(UploadFile file) throws IOException {
        requireMinioFile(file);
        return objectStorage.openObject(file.getBucketName(), file.getObjectKey());
    }

    /** 持久化文件已经载入 RAG 的状态及其向量 Point ID。 */
    public void markLoadedToRag(String fileId, List<String> embeddingIds) {
        markLoadedToRag(fileId, embeddingIds, null);
    }

    /** 同步保存向量配置标识，切换模型后临时附件也必须重新向量化。 */
    public void markLoadedToRag(String fileId, List<String> embeddingIds, String embeddingProfile) {
        UploadFile file = getById(fileId);
        file.setLoadedToRag(true);
        file.setEmbeddingProfile(embeddingProfile);
        file.setEmbeddingIds(embeddingIds == null ? List.of() : List.copyOf(embeddingIds));
        redisCache.setCacheObject(metadataKey(fileId), file);
    }

    /** 清除文件的 RAG 索引状态，供删除或重建索引使用。 */
    public void markUnloadedFromRag(String fileId) {
        UploadFile file = getById(fileId);
        file.setLoadedToRag(false);
        file.setEmbeddingProfile(null);
        file.setEmbeddingIds(List.of());
        redisCache.setCacheObject(metadataKey(fileId), file);
    }

    /**
     * 删除 MinIO 对象；对象删除成功后才删除 Redis 元数据。
     */
    public boolean delete(String fileId) {
        UploadFile file = redisCache.getCacheObject(metadataKey(fileId));
        if (file == null) {
            return false;
        }
        try {
            requireMinioFile(file);
            objectStorage.removeObject(file.getBucketName(), file.getObjectKey());
            redisCache.deleteObject(metadataKey(fileId));
            log.info("已删除 MinIO 文件: fileId={}, bucket={}, objectKey={}",
                    fileId, file.getBucketName(), file.getObjectKey());
            return true;
        } catch (RuntimeException error) {
            log.error("删除 MinIO 文件失败: fileId={}", fileId, error);
            return false;
        }
    }

    /** 生成按日期分区且不包含用户输入的安全 Object Key。 */
    private String objectKey(String storedFileName) {
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        return "uploads/" + datePath + "/" + storedFileName;
    }

    /** 生成隔离的 Redis 元数据 Key。 */
    private String metadataKey(String fileId) {
        if (!StringUtils.hasText(fileId)) {
            throw new IllegalArgumentException("fileId 不能为空");
        }
        return metadataKeyPrefix + fileId.trim();
    }

    /** 仅保留原文件名最后一段，避免路径注入。 */
    private String safeOriginalFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            throw new IllegalArgumentException("原始文件名不能为空");
        }
        String normalized = fileName.replace('\\', '/');
        String result = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (!StringUtils.hasText(result) || result.contains("..")) {
            throw new IllegalArgumentException("原始文件名非法");
        }
        return result;
    }

    /** 获取文件扩展名。 */
    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1);
    }

    /** 校验允许上传的扩展名。 */
    private void validateFileExtension(String fileExtension) {
        if (!StringUtils.hasText(fileExtension)) {
            throw new IllegalArgumentException("文件类型不能为空，支持的类型: " + allowedTypes);
        }
        String normalizedExtension = fileExtension.toLowerCase(Locale.ROOT);
        boolean allowed = Arrays.stream(allowedTypes.split(","))
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(normalizedExtension::equals);
        if (!allowed) {
            throw new IllegalArgumentException("不支持的文件类型: " + fileExtension
                    + "，支持的类型: " + allowedTypes);
        }
    }

    /** 校验元数据确实指向 MinIO 对象。 */
    private void requireMinioFile(UploadFile file) {
        if (file == null || !STORAGE_PROVIDER.equals(file.getStorageProvider())
                || !StringUtils.hasText(file.getBucketName())
                || !StringUtils.hasText(file.getObjectKey())) {
            throw new IllegalStateException("文件元数据不是有效的 MinIO 对象");
        }
    }
}
