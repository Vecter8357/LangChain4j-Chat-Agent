package com.melon.controller;

import com.melon.entity.UploadFile;
import com.melon.service.FileStorageService;
import com.melon.service.StreamingChatService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


/**
 * AI工具统一控制器
 * 处理所有AI工具的请求
 * @author melon
 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/ai")
@Slf4j
public class AIController {
    private static final Set<String> INLINE_IMAGE_SUBTYPES = Set.of("png", "jpeg", "gif", "webp", "avif");

    private final FileStorageService fileStorageService;
    private final StreamingChatService streamingChatService;
    
    /**
     * 统一处理AI工具请求（流式响应）
     * @param request 请求参数
     * @return 流式响应结果
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestBody Map<String, Object> request) {
        String input = (String) request.get("input");
        @SuppressWarnings("unchecked")
        List<String> fileIds = (List<String>) request.get("fileIds");
        
        log.info("收到AI工具流式请求: input={}, fileIds={}", input, fileIds);

        return streamingChatService.processAgentRequest(input, fileIds);
    }
    
    /**
     * 处理文件上传请求
     * @param files 上传的文件列表
     * @return 上传结果
     */
    @PostMapping("/upload")
    public ResponseEntity<List<UploadFile>> uploadFiles(@RequestParam("files") List<MultipartFile> files) {
        log.info("收到文件上传请求，文件数: {}", files.size());
        
        if (files.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        
        try {
            List<UploadFile> uploadedFiles = fileStorageService.storeAll(files);
            return ResponseEntity.ok(uploadedFiles);
        } catch (Exception e) {
            log.error("文件上传失败", e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 流式读取 MinIO 文件。图片允许浏览器内联显示，视频和其他文件默认作为附件下载。
     */
    @GetMapping("/upload/{fileId}/content")
    public ResponseEntity<StreamingResponseBody> fileContent(
            @PathVariable String fileId,
            @RequestParam(defaultValue = "false") boolean download) {
        UploadFile file = fileStorageService.getById(fileId);
        MediaType mediaType = parseMediaType(file.getFileType());
        boolean image = "image".equalsIgnoreCase(mediaType.getType())
                && INLINE_IMAGE_SUBTYPES.contains(mediaType.getSubtype().toLowerCase(Locale.ROOT));
        boolean attachment = download || !image;
        ContentDisposition disposition = (attachment
                ? ContentDisposition.attachment()
                : ContentDisposition.inline())
                .filename(file.getOriginalFileName(), StandardCharsets.UTF_8)
                .build();
        StreamingResponseBody body = output -> {
            try (InputStream input = fileStorageService.openStream(file)) {
                input.transferTo(output);
            }
        };
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(file.getSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(body);
    }
    
    /**
     * 删除上传的文件
     * @param fileId 文件ID
     * @return 删除结果
     */
    @DeleteMapping("/upload/{fileId}")
    public ResponseEntity<Map<String, Object>> deleteFile(@PathVariable String fileId) {
        log.info("收到文件删除请求，文件ID: {}", fileId);
        
        Map<String, Object> response = new HashMap<>();
        try {
            // 从RAG系统移除文件
            streamingChatService.removeFileFromRag(fileId);
            
            // 删除文件
            boolean deleted = fileStorageService.delete(fileId);
            
            if (deleted) {
                response.put("success", true);
                response.put("message", "文件删除成功");
                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("message", "文件不存在或删除失败");
                return ResponseEntity.ok(response);
            }
        } catch (Exception e) {
            log.error("文件删除失败", e);
            response.put("success", false);
            response.put("message", "文件删除失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /** 将未知或非法 MIME 类型安全回退为二进制流。 */
    private MediaType parseMediaType(String value) {
        try {
            return value == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(value);
        } catch (IllegalArgumentException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
    
}
