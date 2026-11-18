package com.melon.config;

import com.melon.config.properties.LangChain4jModelProperties;
import dev.langchain4j.model.image.ImageModel;
import dev.langchain4j.model.openai.OpenAiImageModel;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;

/** 建立独立生图模型或供应商声明的文生图客户端。 */
public final class ImageModelFactory {
    private final LangChain4jModelProperties properties;

    public ImageModelFactory(LangChain4jModelProperties properties) {
        this.properties = properties;
    }

    public Optional<ImageModel> create(String provider) {
        var chat = properties.getChat();
        if ("image".equalsIgnoreCase(provider.trim())) {
            var dedicated = chat.getImage();
            if (!dedicated.isEnabled()) return Optional.empty();
            if (!StringUtils.hasText(dedicated.getApiKey()) || !StringUtils.hasText(dedicated.getModelName())
                    || !StringUtils.hasText(dedicated.getBaseUrl())) {
                throw new IllegalArgumentException("langchain4j.chat.image 已启用，但未配置 api-key、model-name 或 base-url");
            }
            return Optional.of(OpenAiImageModel.builder()
                    .apiKey(dedicated.getApiKey())
                    .baseUrl(dedicated.getBaseUrl())
                    .modelName(dedicated.getModelName())
                    .timeout(dedicated.getTimeout())
                    .logRequests(false)
                    .logResponses(false)
                    .build());
        }
        var config = switch (provider.trim().toLowerCase(Locale.ROOT)) {
            case "openai" -> chat.getOpenai();
            case "zhipu" -> chat.getZhipu();
            case "aihub" -> chat.getAihub();
            default -> null;
        };
        if (config == null || !config.getImageGeneration().isEnabled()) {
            return Optional.empty();
        }
        var image = config.getImageGeneration();
        if (!StringUtils.hasText(config.getApiKey()) || !StringUtils.hasText(image.getModelName())) {
            throw new IllegalArgumentException("文生图已启用，但未配置 API Key 或 image-generation.model-name");
        }
        return Optional.of(OpenAiImageModel.builder()
                .apiKey(config.getApiKey())
                .baseUrl(StringUtils.hasText(image.getBaseUrl()) ? image.getBaseUrl() : config.getBaseUrl())
                .modelName(image.getModelName())
                .timeout(image.getTimeout())
                .logRequests(false)
                .logResponses(false)
                .build());
    }
}
