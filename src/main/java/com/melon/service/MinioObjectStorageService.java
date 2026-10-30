package com.melon.service;

import com.melon.config.properties.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

/**
 * MinIO 对象存储统一入口，负责 Bucket 初始化、上传、临时下载和删除。
 */
@Slf4j
@Service
public class MinioObjectStorageService {

    private final MinioProperties.Config configuration;
    private final MinioClient client;
    private final AtomicBoolean bucketReady = new AtomicBoolean(false);

    /** 根据主 MinIO 配置创建线程安全客户端。 */
    public MinioObjectStorageService(MinioProperties properties) {
        this.configuration = properties.primary();
        this.client = MinioClient.builder()
                .endpoint(configuration.getEndpoint())
                .credentials(configuration.getAccessKey(), configuration.getSecretKey())
                .httpClient(new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
                        .callTimeout(45, TimeUnit.SECONDS).build())
                .build();
    }

    /**
     * 启动时尝试初始化 Bucket。连接暂时不可用时不阻止非文件功能启动，首次操作会再次检查。
     */
    @PostConstruct
    public void initialize() {
        try {
            ensureBucket();
        } catch (RuntimeException error) {
            log.warn("MinIO 初始化暂时失败，首次文件操作时将重试: endpoint={}, bucket={}, error={}",
                    configuration.getEndpoint(), configuration.getBucket(), error.getMessage());
        }
    }

    /** 返回当前主 Bucket 名称。 */
    public String bucket() {
        return configuration.getBucket();
    }

    /** 上传一个对象。 */
    public void putObject(String objectKey, InputStream input, long size, String contentType) {
        requireObjectKey(objectKey);
        ensureBucket();
        try {
            PutObjectArgs.Builder builder = PutObjectArgs.builder()
                    .bucket(configuration.getBucket())
                    .object(objectKey)
                    .stream(input, size, -1);
            if (StringUtils.hasText(contentType)) {
                builder.contentType(contentType);
            }
            client.putObject(builder.build());
        } catch (Exception error) {
            throw new IllegalStateException("MinIO 对象上传失败: " + objectKey, error);
        }
    }

    /**
     * 将对象下载到独立临时目录，供 Docling 等必须使用本地路径的工具处理。
     */
    public MaterializedObject materialize(String bucket, String objectKey, String extension) throws IOException {
        requireObjectKey(objectKey);
        ensureBucket();
        String targetBucket = StringUtils.hasText(bucket) ? bucket : configuration.getBucket();
        Path directory = Files.createTempDirectory("la4j-agent-upload-");
        String safeExtension = extension == null ? "" : extension.replaceAll("[^A-Za-z0-9]", "");
        Path target = directory.resolve("source" + (safeExtension.isBlank() ? "" : "." + safeExtension));
        try (GetObjectResponse response = client.getObject(GetObjectArgs.builder()
                .bucket(targetBucket)
                .object(objectKey)
                .build())) {
            Files.copy(response, target, StandardCopyOption.REPLACE_EXISTING);
            return new MaterializedObject(directory, target);
        } catch (Exception error) {
            deleteQuietly(target);
            deleteQuietly(directory);
            if (error instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("MinIO 对象下载失败: " + objectKey, error);
        }
    }

    /**
     * 打开对象输入流。调用方必须关闭返回值，适用于 HTTP 流式下载而不落地临时文件。
     */
    public InputStream openObject(String bucket, String objectKey) throws IOException {
        requireObjectKey(objectKey);
        ensureBucket();
        String targetBucket = StringUtils.hasText(bucket) ? bucket : configuration.getBucket();
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(targetBucket)
                    .object(objectKey)
                    .build());
        } catch (Exception error) {
            throw new IOException("MinIO 对象读取失败: " + objectKey, error);
        }
    }

    /** 删除对象；MinIO 对不存在对象的删除操作保持幂等。 */
    public void removeObject(String bucket, String objectKey) {
        requireObjectKey(objectKey);
        ensureBucket();
        String targetBucket = StringUtils.hasText(bucket) ? bucket : configuration.getBucket();
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(targetBucket)
                    .object(objectKey)
                    .build());
        } catch (Exception error) {
            throw new IllegalStateException("MinIO 对象删除失败: " + objectKey, error);
        }
    }

    /** 确保 Bucket 存在；并发首次调用只会执行一次远程检查。 */
    private synchronized void ensureBucket() {
        if (bucketReady.get()) {
            return;
        }
        try {
            String bucket = configuration.getBucket();
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("已创建 MinIO Bucket: endpoint={}, bucket={}", configuration.getEndpoint(), bucket);
            }
            bucketReady.set(true);
        } catch (Exception error) {
            throw new IllegalStateException("无法连接或初始化 MinIO Bucket: " + configuration.getBucket(), error);
        }
    }

    /** 校验对象 Key，禁止空值和绝对路径式 Key。 */
    private void requireObjectKey(String objectKey) {
        if (!StringUtils.hasText(objectKey) || objectKey.startsWith("/") || objectKey.contains("..")) {
            throw new IllegalArgumentException("非法 MinIO objectKey");
        }
    }

    /** 静默删除本服务创建的单个临时路径。 */
    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时目录由系统后续清理，关闭资源时不覆盖主异常。
        }
    }

    /**
     * 下载到本地的临时对象，关闭时删除文件和其专属临时目录。
     */
    public static final class MaterializedObject implements AutoCloseable {
        private final Path directory;
        private final Path path;

        private MaterializedObject(Path directory, Path path) {
            this.directory = directory;
            this.path = path;
        }

        /** 返回可供本地解析器读取的路径。 */
        public Path path() {
            return path;
        }

        /** 清理临时文件。 */
        @Override
        public void close() {
            deleteQuietly(path);
            deleteQuietly(directory);
        }
    }
}
