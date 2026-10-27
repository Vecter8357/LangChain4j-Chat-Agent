package com.melon.config;

import com.melon.config.properties.LangChain4jModelProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Locale;

@Configuration
@Slf4j
public class LangChain4jChatConfig {

    @Bean
    @Primary
    public ChatModel chatLanguageModel(LangChain4jModelProperties properties) {
        String provider = normalizeProvider(properties.getChat().resolvedProvider());
        log.info("Initializing chat model provider: {}", provider);

        return switch (provider) {
            case "ollama" -> buildOllamaChatModel(properties.getChat().getOllama());
            case "openai" -> buildOpenAiChatModel(properties.getChat().getOpenai());
            case "zhipu" -> buildOpenAiChatModel(properties.getChat().getZhipu());
            case "aihub" -> buildOpenAiChatModel(properties.getChat().getAihub());
            default -> throw new IllegalStateException("Unsupported chat provider: " + provider);
        };
    }

    private ChatModel buildOllamaChatModel(LangChain4jModelProperties.OllamaChatProperties config) {
        return OllamaChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .timeout(config.getTimeout())
                .temperature(config.getTemperature())
                .build();
    }

    private ChatModel buildOpenAiChatModel(LangChain4jModelProperties.OpenAiCompatibleProperties config) {
        return OpenAiChatModel.builder()
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
