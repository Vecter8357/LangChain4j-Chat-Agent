package com.melon.config;

import com.melon.config.properties.LangChain4jModelProperties;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Locale;

@Configuration
@Slf4j
public class LangChain4jStreamConfig {

    @Bean
    @Primary
    public StreamingChatModel streamLanguageModel(LangChain4jModelProperties properties) {
        String provider = normalizeProvider(properties.getChat().resolvedProvider());
        log.info("Initializing streaming chat model provider: {}", provider);

        return switch (provider) {
            case "ollama" -> buildOllamaStreamingModel(properties.getChat().getOllama());
            case "openai" -> buildOpenAiStreamingModel(properties.getChat().getOpenai());
            case "zhipu" -> buildOpenAiStreamingModel(properties.getChat().getZhipu());
            case "aihub" -> buildOpenAiStreamingModel(properties.getChat().getAihub());
            default -> throw new IllegalStateException("Unsupported streaming chat provider: " + provider);
        };
    }

    private StreamingChatModel buildOllamaStreamingModel(LangChain4jModelProperties.OllamaChatProperties config) {
        return OllamaStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .temperature(config.getTemperature())
                .timeout(config.getTimeout())
                .logRequests(true)
                .logResponses(true)
                .modelName(config.getModelName())
                .build();
    }

    private StreamingChatModel buildOpenAiStreamingModel(LangChain4jModelProperties.OpenAiCompatibleProperties config) {
        return OpenAiStreamingChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .baseUrl(config.getBaseUrl())
                .timeout(config.getTimeout())
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    private String normalizeProvider(String provider) {
        return provider == null ? "ollama" : provider.trim().toLowerCase(Locale.ROOT);
    }
}
