package com.melon.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "langchain4j")
public class LangChain4jModelProperties {

    private ChatProperties chat = new ChatProperties();

    private EmbeddingProperties embedding = new EmbeddingProperties();

    @Data
    public static class ChatProperties {

        private String provider;

        private String enable;

        private OllamaChatProperties ollama = new OllamaChatProperties();

        private OpenAiCompatibleProperties openai = new OpenAiCompatibleProperties();

        private OpenAiCompatibleProperties zhipu = new OpenAiCompatibleProperties();

        private OpenAiCompatibleProperties aihub = new OpenAiCompatibleProperties();

        public String resolvedProvider() {
            if (StringUtils.hasText(provider)) {
                return provider.trim();
            }
            if (StringUtils.hasText(enable)) {
                return enable.trim();
            }
            return "ollama";
        }
    }

    @Data
    public static class EmbeddingProperties {

        private String provider;

        private String enable;

        private OllamaEmbeddingProperties ollama = new OllamaEmbeddingProperties();

        private RemoteEmbeddingProperties siliconflow = new RemoteEmbeddingProperties();

        public String resolvedProvider() {
            if (StringUtils.hasText(provider)) {
                return provider.trim();
            }
            if (StringUtils.hasText(enable)) {
                return enable.trim();
            }
            return "siliconflow";
        }

        /** 供索引版本与模型配置共用，避免切换提供方后仍记录 Ollama 模型。 */
        public String resolvedModelName() {
            return "ollama".equalsIgnoreCase(resolvedProvider()) ? ollama.getModelName() : siliconflow.getModelName();
        }

        /** 不含密钥的服务地址，可纳入向量空间版本标识。 */
        public String resolvedBaseUrl() {
            return "ollama".equalsIgnoreCase(resolvedProvider()) ? ollama.getBaseUrl() : siliconflow.getBaseUrl();
        }
    }

    /** 硅基流动使用 OpenAI 兼容 Embedding 协议，密钥只从外部配置读取。 */
    @Data
    public static class RemoteEmbeddingProperties {
        private String baseUrl = "https://api.siliconflow.cn/v1";
        @lombok.ToString.Exclude
        private String apiKey;
        private String modelName = "BAAI/bge-m3";
        private Duration timeout = Duration.ofSeconds(60);
        private int maxSegmentsPerBatch = 16;
    }

    @Data
    public static class OllamaChatProperties {

        private String baseUrl;

        private String modelName;

        private Duration timeout = Duration.ofSeconds(60);

        private Double temperature = 0.0;
    }

    @Data
    public static class OllamaEmbeddingProperties {

        private String baseUrl;

        private String modelName;

        private Duration timeout = Duration.ofSeconds(60);
    }

    @Data
    public static class OpenAiCompatibleProperties {

        private String apiKey;

        private String modelName;

        private Double temperature = 0.0;

        private Integer maxTokens;

        private String baseUrl;

        private Duration timeout = Duration.ofSeconds(60);

        private String name;
    }
}
