package com.melon.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private SplitterProperties splitter = new SplitterProperties();

    private VectorStoreProperties vectorStore = new VectorStoreProperties();

    @Data
    public static class SplitterProperties {

        private int maxSegmentSize = 800;

        private int maxOverlapSize = 120;

        private int maxResults = 6;

        private double minScore = 0.55;
    }

    /** RAG 向量存储选择与连接参数。 */
    @Data
    public static class VectorStoreProperties {

        /** 可选值：qdrant、in-memory。 */
        private String provider = "qdrant";

        private QdrantProperties qdrant = new QdrantProperties();
    }

    /** Qdrant 使用 gRPC 接口，默认端口为 6334。 */
    @Data
    public static class QdrantProperties {

        private String host = "localhost";

        private int port = 6334;

        private boolean useTls = false;

        private String apiKey;

        private String collectionName = "la4j_agent_sf_bge_m3";

        private String payloadTextKey = "text_segment";

        /** 必须与当前 Embedding 模型的输出维度一致。 */
        private int dimension = 1024;

        /** COSINE、EUCLID、DOT 或 MANHATTAN。 */
        private String distance = "COSINE";

        private boolean onDisk = false;

        private boolean autoCreateCollection = true;

        private Duration timeout = Duration.ofSeconds(10);
    }
}
