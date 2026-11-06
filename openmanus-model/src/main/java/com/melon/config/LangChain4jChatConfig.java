package com.melon.config;

import com.melon.config.properties.LangChain4jModelProperties;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Locale;

@Configuration
@Slf4j
public class LangChain4jChatConfig {

    /** 按聊天配置构造应用默认的同步 ChatModel。 */
    @Bean
    @Primary
    public ChatModel chatLanguageModel(LangChain4jModelProperties properties) {
        String provider = normalizeProvider(properties.getChat().resolvedProvider());
        log.info("Initializing chat model provider: {}", provider);

        return new ChatModelFactory(properties).create(provider);
    }

    /** 将供应商名称去除首尾空白并转为小写，空引用使用 Ollama。 */
    private String normalizeProvider(String provider) {
        return provider == null ? "ollama" : provider.trim().toLowerCase(Locale.ROOT);
    }
}
