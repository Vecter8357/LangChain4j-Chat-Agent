package com.melon.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.config.properties.LangChain4jModelProperties;
import com.melon.config.properties.RewriteWorkflowProperties;
import com.melon.workflow.model.ModelRole;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class WorkflowModelGateway {

    private final Map<String, ChatModel> modelBeans;
    private final ChatModel defaultModel;
    private final RewriteWorkflowProperties properties;
    private final LangChain4jModelProperties modelProperties;
    private final ObjectMapper objectMapper;
    private final Map<ModelRole, ChatModel> roleModelCache = new ConcurrentHashMap<>();

    public WorkflowModelGateway(Map<String, ChatModel> modelBeans,
                                @Qualifier("chatLanguageModel") ChatModel defaultModel,
                                RewriteWorkflowProperties properties,
                                LangChain4jModelProperties modelProperties,
                                ObjectMapper objectMapper) {
        this.modelBeans = modelBeans;
        this.defaultModel = defaultModel;
        this.properties = properties;
        this.modelProperties = modelProperties;
        this.objectMapper = objectMapper;
    }

    public String text(ModelRole role, String instruction, String input) {
        String prompt = """
                你正在执行文学改编工作流中的一个独立节点。
                角色：%s

                节点指令：
                %s

                输入数据：
                %s
                """.formatted(role.name(), instruction, input);
        return model(role).chat(prompt).trim();
    }

    public <T> T json(ModelRole role, String instruction, String input, Class<T> resultType) {
        String jsonInstruction = instruction + "\n只返回一个合法 JSON 对象，不要使用 Markdown 代码块，不要补充解释。";
        String raw = text(role, jsonInstruction, input);
        try {
            return parseJson(raw, resultType);
        } catch (Exception firstError) {
            log.warn("角色 {} 首次返回的 JSON 无法解析，正在请求修复: {}", role, firstError.getMessage());
            String repairInput = "目标类型：" + resultType.getSimpleName()
                    + "\n原始字段及结构要求：\n" + instruction
                    + "\n解析器错误：\n" + firstError.getMessage()
                    + "\n待修复内容：\n" + raw;
            String repaired = text(role,
                    "根据字段要求和解析器错误修复 JSON。字段类型必须完全匹配，只返回修复后的 JSON 对象。",
                    repairInput);
            try {
                return parseJson(repaired, resultType);
            } catch (Exception secondError) {
                throw new WorkflowExecutionException("模型角色 " + role + " 未返回可解析的 JSON", secondError);
            }
        }
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new WorkflowExecutionException("无法序列化工作流上下文", e);
        }
    }

    private <T> T parseJson(String raw, Class<T> resultType) throws JsonProcessingException {
        String normalized = extractJsonObject(raw);
        return objectMapper.readerFor(resultType)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .readValue(normalized);
    }

    private String extractJsonObject(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("模型返回为空");
        }
        String value = raw.trim();
        if (value.startsWith("```")) {
            int firstLineBreak = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstLineBreak >= 0 && lastFence > firstLineBreak) {
                value = value.substring(firstLineBreak + 1, lastFence).trim();
            }
        }
        int firstBrace = value.indexOf('{');
        int lastBrace = value.lastIndexOf('}');
        if (firstBrace < 0 || lastBrace < firstBrace) {
            throw new IllegalArgumentException("没有找到 JSON 对象");
        }
        return value.substring(firstBrace, lastBrace + 1);
    }

    private ChatModel model(ModelRole role) {
        String beanName = properties.getModelByRole().get(role);
        if (StringUtils.hasText(beanName)) {
            ChatModel selected = modelBeans.get(beanName);
            if (selected == null) {
                throw new WorkflowExecutionException("角色 " + role + " 配置的模型 Bean 不存在: " + beanName);
            }
            return selected;
        }

        String provider = properties.getProviderByRole().get(role);
        if (!StringUtils.hasText(provider)) {
            return defaultModel;
        }
        return roleModelCache.computeIfAbsent(role, ignored -> buildProviderModel(provider));
    }

    private ChatModel buildProviderModel(String provider) {
        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        log.info("为改编工作流创建角色模型，provider={}", normalized);
        return switch (normalized) {
            case "ollama" -> buildOllama(modelProperties.getChat().getOllama());
            case "openai" -> buildOpenAiCompatible(modelProperties.getChat().getOpenai());
            case "zhipu" -> buildOpenAiCompatible(modelProperties.getChat().getZhipu());
            case "aihub" -> buildOpenAiCompatible(modelProperties.getChat().getAihub());
            default -> throw new WorkflowExecutionException("工作流角色配置了不支持的 provider: " + provider);
        };
    }

    private ChatModel buildOllama(LangChain4jModelProperties.OllamaChatProperties config) {
        return OllamaChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .timeout(config.getTimeout())
                .temperature(config.getTemperature())
                .build();
    }

    private ChatModel buildOpenAiCompatible(LangChain4jModelProperties.OpenAiCompatibleProperties config) {
        if (!StringUtils.hasText(config.getApiKey())) {
            throw new WorkflowExecutionException("角色模型 provider 未配置 API Key");
        }
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
}
