package com.melon.config;

import com.melon.config.properties.LangChain4jModelProperties;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Locale;

@Configuration
@Slf4j
public class LangChain4jEmbeddingConfig {

    @Bean
    @Primary
    public EmbeddingModel embeddingLanguageModel(LangChain4jModelProperties properties) {
        String provider = normalizeProvider(properties.getEmbedding().resolvedProvider());
        log.info("Initializing embedding model provider: {}", provider);

        if ("siliconflow".equals(provider)) {
            var config = properties.getEmbedding().getSiliconflow();
            if (!StringUtils.hasText(config.getApiKey())) {
                throw new IllegalStateException("硅基流动 Embedding 密钥缺失，请设置 GJLD_EMBEDDING 并重启进程");
            }
            if (!StringUtils.hasText(config.getModelName()) || config.getModelName().toLowerCase(Locale.ROOT).contains("reranker")) {
                throw new IllegalArgumentException("Embedding 必须配置向量模型，例如 BAAI/bge-m3；reranker 模型仅用于重排");
            }
            return OpenAiEmbeddingModel.builder().baseUrl(config.getBaseUrl()).apiKey(config.getApiKey())
                    .modelName(config.getModelName()).timeout(config.getTimeout())
                    .maxSegmentsPerBatch(config.getMaxSegmentsPerBatch()).maxRetries(2)
                    .encodingFormat("float")
                    .logRequests(false).logResponses(false).build();
        }

        if (!"ollama".equals(provider)) {
            throw new IllegalStateException("Unsupported embedding provider: " + provider);
        }

        LangChain4jModelProperties.OllamaEmbeddingProperties config = properties.getEmbedding().getOllama();
        return OllamaEmbeddingModel.builder()
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .timeout(config.getTimeout())
                .build();
    }

    private String normalizeProvider(String provider) {
        return provider == null ? "siliconflow" : provider.trim().toLowerCase(Locale.ROOT);
    }
}
