package com.melon.knowledge;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.time.Duration;

/** 第一版知识平台的存储、索引、检索及重排参数。 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge")
public class KnowledgeProperties {
    private String datasource = "szc_frjzk";
    private boolean initializeSchema = true;
    private int workers = 2;
    private String embeddingRevision = "siliconflow-bge-m3-v1";
    private int recallLimit = 30;
    private int resultLimit = 6;
    private Rerank rerank = new Rerank();

    /** remote 模式使用兼容 Jina/Cohere 的 /rerank JSON 接口。 */
    @Data
    public static class Rerank {
        private String provider = "local";
        private String url;
        private String apiKey;
        private String model = "BAAI/bge-reranker-v2-m3";
        private Duration timeout = Duration.ofSeconds(30);
    }
}
