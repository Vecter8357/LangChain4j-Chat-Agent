package com.melon.config;

import com.melon.config.properties.LangChain4jModelProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.util.StringUtils;

import java.util.Locale;

public final class ChatModelFactory {

    private final LangChain4jModelProperties modelProperties;

    /** 保存各模型供应商的连接配置，供运行时按需创建客户端。 */
    public ChatModelFactory(LangChain4jModelProperties modelProperties) {
        this.modelProperties = modelProperties;
    }

    /** 按供应商标识选择聊天客户端实现，未知供应商触发配置错误。 */
    public ChatModel create(String provider) {
        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "ollama" -> buildOllama(modelProperties.getChat().getOllama());
            case "openai" -> buildOpenAiCompatible(modelProperties.getChat().getOpenai());
            case "zhipu" -> buildOpenAiCompatible(modelProperties.getChat().getZhipu());
            case "aihub" -> buildOpenAiCompatible(modelProperties.getChat().getAihub());
            default -> throw new IllegalArgumentException("Unsupported chat provider: " + provider);
        };
    }

    /** 使用配置的地址、模型、超时和温度构造 Ollama 聊天客户端。 */
    private ChatModel buildOllama(LangChain4jModelProperties.OllamaChatProperties config) {
        return OllamaChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .timeout(config.getTimeout())
                .temperature(config.getTemperature())
                .build();
    }

    /** 校验 API Key 并构造 OpenAI 兼容客户端，同时关闭完整请求和响应日志。 */
    private ChatModel buildOpenAiCompatible(LangChain4jModelProperties.OpenAiCompatibleProperties config) {
        if (!StringUtils.hasText(config.getApiKey())) {
            throw new IllegalArgumentException("角色模型 provider 未配置 API Key");
        }
        return OpenAiChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .baseUrl(config.getBaseUrl())
                .timeout(config.getTimeout())
                .logRequests(false)
                .logResponses(false)
                .build();
    }
}
