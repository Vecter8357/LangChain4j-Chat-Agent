package com.melon.config;

import com.melon.config.properties.RagProperties;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

/**
 * 创建 RAG 使用的向量存储。生产配置默认使用 Qdrant，测试或离线开发可切换为 in-memory。
 */
@Configuration
@Slf4j
public class EmbeddingStoreConfig {

    /** 创建进程内向量存储，仅用于测试或无 Qdrant 的临时开发环境。 */
    @Bean
    @ConditionalOnProperty(prefix = "rag.vector-store", name = "provider", havingValue = "in-memory")
    public EmbeddingStore<TextSegment> inMemoryEmbeddingStore() {
        log.warn("RAG 正在使用进程内向量存储，应用重启后索引会丢失");
        return new InMemoryEmbeddingStore<>();
    }

    /**
     * 创建 Qdrant 向量存储，并在启动时检查或创建 Collection。
     * Collection 已存在时会校验向量维度和距离算法，避免运行到首次写入才报错。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(
            prefix = "rag.vector-store",
            name = "provider",
            havingValue = "qdrant",
            matchIfMissing = true)
    public QdrantEmbeddingStore qdrantEmbeddingStore(RagProperties ragProperties) {
        RagProperties.QdrantProperties config = ragProperties.getVectorStore().getQdrant();
        validate(config);
        QdrantClient client = createClient(config);
        try {
            ensureCollection(client, config);
            log.info("Qdrant 向量存储已就绪: host={}:{}, collection={}, dimension={}, distance={}",
                    config.getHost(), config.getPort(), config.getCollectionName(),
                    config.getDimension(), resolveDistance(config.getDistance()));
            return QdrantEmbeddingStore.builder()
                    .client(client)
                    .collectionName(config.getCollectionName())
                    .payloadTextKey(config.getPayloadTextKey())
                    .build();
        } catch (RuntimeException error) {
            client.close();
            throw error;
        }
    }

    /** 根据配置创建带超时和可选 API Key 的 Qdrant gRPC 客户端。 */
    QdrantClient createClient(RagProperties.QdrantProperties config) {
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(
                        config.getHost(), config.getPort(), config.isUseTls())
                .withTimeout(config.getTimeout());
        if (StringUtils.hasText(config.getApiKey())) {
            builder.withApiKey(config.getApiKey().trim());
        }
        return new QdrantClient(builder.build());
    }

    /** 检查 Collection；不存在时按配置创建，存在时验证核心参数。 */
    void ensureCollection(QdrantClient client, RagProperties.QdrantProperties config) {
        Duration timeout = config.getTimeout();
        try {
            boolean exists = client.collectionExistsAsync(config.getCollectionName(), timeout).get();
            if (!exists) {
                if (!config.isAutoCreateCollection()) {
                    throw new IllegalStateException("Qdrant Collection 不存在且已关闭自动创建: "
                            + config.getCollectionName());
                }
                Collections.VectorParams vectorParams = Collections.VectorParams.newBuilder()
                        .setSize(config.getDimension())
                        .setDistance(resolveDistance(config.getDistance()))
                        .setOnDisk(config.isOnDisk())
                        .build();
                boolean created = client.createCollectionAsync(
                        config.getCollectionName(), vectorParams, timeout).get().getResult();
                if (!created) {
                    throw new IllegalStateException("Qdrant Collection 创建未成功: " + config.getCollectionName());
                }
                return;
            }

            Collections.CollectionInfo info = client.getCollectionInfoAsync(
                    config.getCollectionName(), timeout).get();
            Collections.VectorParams actual = info.getConfig().getParams()
                    .getVectorsConfig().getParams();
            Collections.Distance expectedDistance = resolveDistance(config.getDistance());
            if (actual.getSize() != config.getDimension()) {
                throw new IllegalStateException("Qdrant Collection 向量维度不匹配: configured="
                        + config.getDimension() + ", actual=" + actual.getSize());
            }
            if (actual.getDistance() != expectedDistance) {
                throw new IllegalStateException("Qdrant Collection 距离算法不匹配: configured="
                        + expectedDistance + ", actual=" + actual.getDistance());
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("初始化 Qdrant Collection 时线程被中断", error);
        } catch (ExecutionException error) {
            throw new IllegalStateException("无法初始化 Qdrant Collection: " + config.getCollectionName(),
                    error.getCause());
        }
    }

    /** 将外部配置名称转换为 Qdrant 距离枚举。 */
    Collections.Distance resolveDistance(String value) {
        return switch (value == null ? "COSINE" : value.trim().toUpperCase(Locale.ROOT)) {
            case "COSINE" -> Collections.Distance.Cosine;
            case "EUCLID", "EUCLIDEAN" -> Collections.Distance.Euclid;
            case "DOT" -> Collections.Distance.Dot;
            case "MANHATTAN" -> Collections.Distance.Manhattan;
            default -> throw new IllegalArgumentException("不支持的 Qdrant 距离算法: " + value);
        };
    }

    /** 在建立网络连接前校验必填项，输出可定位的启动错误。 */
    private void validate(RagProperties.QdrantProperties config) {
        if (!StringUtils.hasText(config.getHost())) {
            throw new IllegalArgumentException("rag.vector-store.qdrant.host 不能为空");
        }
        if (!StringUtils.hasText(config.getCollectionName())) {
            throw new IllegalArgumentException("rag.vector-store.qdrant.collection-name 不能为空");
        }
        if (!StringUtils.hasText(config.getPayloadTextKey())) {
            throw new IllegalArgumentException("rag.vector-store.qdrant.payload-text-key 不能为空");
        }
        if (config.getPort() <= 0 || config.getPort() > 65535) {
            throw new IllegalArgumentException("Qdrant gRPC 端口必须在 1-65535 之间");
        }
        if (config.getDimension() <= 0) {
            throw new IllegalArgumentException("Qdrant 向量维度必须大于 0");
        }
        if (config.getTimeout() == null || config.getTimeout().isNegative() || config.getTimeout().isZero()) {
            throw new IllegalArgumentException("Qdrant 超时时间必须大于 0");
        }
        resolveDistance(config.getDistance());
    }
}
