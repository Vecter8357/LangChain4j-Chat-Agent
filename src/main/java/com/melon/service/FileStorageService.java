package com.melon.service;

import com.melon.config.RedisCache;
import com.melon.entity.UploadFile;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 文件存储服务类
 * 负责处理文件的上传和存储
 */
@Service
@Slf4j
public class FileStorageService {

    @Value("${file.upload.dir}")
    private String uploadDir;

    @Value("${file.upload.max-size}") // 默认10MB
    private long maxFileSize;

    @Value("${file.upload.allowed-types}")
    private String allowedTypes;

    @Autowired
    private RedisCache redisCache;

    /**
     * 初始化存储目录
     */
    @PostConstruct
    public void init() {
        try {
            Path path = Paths.get(uploadDir);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
                log.info("创建文件上传目录: {}", path.toAbsolutePath());
            }
        } catch (IOException e) {
            log.error("无法创建文件上传目录: {}", uploadDir, e);
            throw new RuntimeException("无法创建文件上传目录", e);
        }
    }

    /**
     * 存储单个文件
     *
     * @param file 上传的文件
     * @return 存储的文件信息
     */
    public UploadFile store(MultipartFile file) {
        // 检查文件大小
        if (file.getSize() > maxFileSize) {
            throw new RuntimeException("文件太大，最大允许大小为: " + (maxFileSize / 1024 / 1024) + "MB");
        }

        try {
            // 生成唯一文件名
            String fileId = UUID.randomUUID().toString();
            String originalFileName = file.getOriginalFilename();
            String fileExtension = getFileExtension(originalFileName);
            validateFileExtension(fileExtension);
            String storedFileName = fileId + (fileExtension != null ? "." + fileExtension : "");

            // 保存文件
            Path targetPath = Paths.get(uploadDir, storedFileName);
            Files.copy(file.getInputStream(), targetPath);

            // 创建文件记录
            UploadFile uploadFile = UploadFile.builder()
                    .id(fileId)
                    .originalFileName(originalFileName)
                    .storedFileName(storedFileName)
                    .filePath(targetPath.toString())
                    .size(file.getSize())
                    .fileType(file.getContentType())
                    .uploadTime(LocalDateTime.now())
                    .loadedToRag(false)
                    .build();

            // 保存到内存中
            redisCache.setCacheObject(fileId, uploadFile,5, TimeUnit.MINUTES);
            
            log.info("成功存储文件: {}, 路径: {}", originalFileName, targetPath);
            return uploadFile;
        } catch (IOException e) {
            log.error("存储文件失败: {}", file.getOriginalFilename(), e);
            throw new RuntimeException("存储文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 存储多个文件
     *
     * @param files 上传的文件列表
     * @return 存储的文件信息列表
     */
    public List<UploadFile> storeAll(List<MultipartFile> files) {
        List<UploadFile> result = new ArrayList<>();
        for (MultipartFile file : files) {
            if (!file.isEmpty()) {
                result.add(store(file));
            }
        }
        return result;
    }

    /**
     * 根据ID获取文件信息
     *
     * @param fileId 文件ID
     * @return 文件信息
     */
    public UploadFile getById(String fileId) {
        UploadFile file = redisCache.getCacheObject(fileId);
        if (file == null) {
            throw new RuntimeException("文件不存在，ID: " + fileId);
        }
        return file;
    }

    /**
     * 根据ID列表获取多个文件信息
     *
     * @param fileIds 文件ID列表
     * @return 文件信息列表
     */
    public List<UploadFile> getByIds(List<String> fileIds) {
        List<UploadFile> result = new ArrayList<>();
        if (fileIds != null) {
            for (String fileId : fileIds) {
                UploadFile file = redisCache.getCacheObject(fileId);
                if (file != null) {
                    result.add(file);
                }
            }
        }
        return result;
    }

    /**
     * 删除文件
     *
     * @param fileId 文件ID
     * @return 是否删除成功
     */
    public boolean delete(String fileId) {
        UploadFile file = redisCache.getCacheObject(fileId);
        if (file != null) {
            try {
                Path filePath = Paths.get(file.getFilePath());
                if (Files.exists(filePath)) {
                    Files.delete(filePath);
                }
                redisCache.deleteObject(fileId);
                log.info("删除文件: {}", file.getOriginalFileName());
                return true;
            } catch (IOException e) {
                log.error("删除文件失败: {}", file.getOriginalFileName(), e);
                return false;
            }
        }
        return false;
    }

    /**
     * 获取文件扩展名
     *
     * @param fileName 文件名
     * @return 扩展名
     */
    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return null;
        }
        return fileName.substring(fileName.lastIndexOf(".") + 1);
    }

    private void validateFileExtension(String fileExtension) {
        if (fileExtension == null) {
            throw new RuntimeException("文件类型不能为空，支持的类型: " + allowedTypes);
        }

        String normalizedExtension = fileExtension.toLowerCase();
        boolean allowed = Arrays.stream(allowedTypes.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .anyMatch(normalizedExtension::equals);

        if (!allowed) {
            throw new RuntimeException("不支持的文件类型: " + fileExtension + "，支持的类型: " + allowedTypes);
        }
    }
}
