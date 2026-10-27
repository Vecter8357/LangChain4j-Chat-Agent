package com.melon.config;

import com.melon.config.properties.LangChain4jModelProperties;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
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
        return provider == null ? "ollama" : provider.trim().toLowerCase(Locale.ROOT);
    }
}
