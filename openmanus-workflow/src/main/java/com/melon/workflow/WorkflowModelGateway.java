package com.melon.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.config.ChatModelFactory;
import com.melon.config.properties.LangChain4jModelProperties;
import com.melon.config.properties.RewriteWorkflowProperties;
import com.melon.workflow.model.ModelRole;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Locale;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class WorkflowModelGateway {

    private final Map<String, ChatModel> modelBeans;
    private final ChatModel defaultModel;
    private final RewriteWorkflowProperties properties;
    private final ChatModelFactory modelFactory;
    private final ObjectMapper objectMapper;
    private final Map<ModelRole, ChatModel> roleModelCache = new ConcurrentHashMap<>();

    /** 装配角色模型选择、默认模型和结构化输出处理所需的依赖。 */
    public WorkflowModelGateway(Map<String, ChatModel> modelBeans,
                                @Qualifier("chatLanguageModel") ChatModel defaultModel,
                                RewriteWorkflowProperties properties,
                                LangChain4jModelProperties modelProperties,
                                ObjectMapper objectMapper) {
        this.modelBeans = modelBeans;
        this.defaultModel = defaultModel;
        this.properties = properties;
        this.modelFactory = new ChatModelFactory(modelProperties);
        this.objectMapper = objectMapper;
    }

    /** 为模型角色组装节点指令，收到空响应时重试一次并返回去除首尾空白的文本。 */
    public String text(ModelRole role, String instruction, String input) {
        return generate(role, instruction, input, false).aiMessage().text().trim();
    }

    /** 结构化节点使用 JSON 模式并将指令与输入分离，保留响应元数据供失败诊断。 */
    private ChatResponse generate(ModelRole role, String instruction, String input, boolean structured) {
        String prompt = """
                你正在执行文学改编工作流中的一个独立节点。
                角色：%s

                节点指令：
                %s

                输入数据：
                %s
                """.formatted(role.name(), instruction, input);
        ChatModel selectedModel = model(role);
        ChatRequest request = structured
                ? ChatRequest.builder()
                    .messages(SystemMessage.from("你正在执行文学改编工作流中的一个独立节点。\n角色："
                            + role.name() + "\n节点指令：\n" + instruction), UserMessage.from(input))
                    .responseFormat(ResponseFormat.JSON)
                    .build()
                : ChatRequest.builder().messages(UserMessage.from(prompt)).build();
        long started = System.nanoTime();
        // 只重试空响应；网络和提供方异常由模型客户端处理，避免叠加重试。
        for (int attempt = 1; attempt <= 2; attempt++) {
            ChatResponse response;
            try {
                response = selectedModel.chat(request);
            } catch (RuntimeException error) {
                if (!isTimeout(error)) throw error;
                long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
                String route = modelRoute(role);
                log.warn("工作流模型请求超时，role={}，route={}，elapsedMs={}，inputCharacters={}",
                        role, route, elapsed, prompt.length());
                throw new WorkflowExecutionException("模型角色 " + role + " 请求超时（" + route
                        + "，耗时 " + elapsed + "ms）。请检查对应模型的 HTTP timeout 配置与服务响应状态。", error);
            }
            AiMessage message = response == null ? null : response.aiMessage();
            String content = message == null ? null : message.text();
            if (StringUtils.hasText(content)) {
                rejectClientRestriction(role, response);
                return response;
            }
            String details = response == null ? "response=null"
                    : "model=" + response.modelName() + ", finishReason=" + response.finishReason();
            if (attempt == 2) {
                throw new WorkflowExecutionException("模型角色 " + role
                        + " 连续 2 次返回空文本（" + details
                        + "）。请检查模型服务响应；若 finishReason=LENGTH，请增加输出 token 上限或缩短输入。");
            }
            log.warn("模型角色 {} 返回空文本，正在重试一次: {}", role, details);
        }
        throw new IllegalStateException("模型调用未执行");
    }

    /** 部分网关以 HTTP 200 返回客户端限制提示，不能将其当作模型正文继续处理或修复。 */
    private void rejectClientRestriction(ModelRole role, ChatResponse response) {
        String content = response.aiMessage().text().trim();
        if (content.startsWith("本站仅限") && content.contains("当前客户端不支持")) {
            log.warn("模型网关拒绝当前客户端，role={}，{}", role, responseDetails(role, response));
            throw new WorkflowExecutionException("模型角色 " + role + " 请求被模型网关拒绝（"
                    + responseDetails(role, response) + "）：该网关仅限指定 Coding 客户端/CLI 使用，当前客户端不支持。"
                    + "请将 langchain4j.chat.provider 或 rewrite-workflow.provider-by-role 切换到允许普通 API 调用的供应商，"
                    + "并配置对应的 base-url 与 API Key。JSON 修复重试无法解决此限制。");
        }
    }

    /** 识别供应商包装的请求、连接和套接字超时，保留其他异常的原有传播方式。 */
    private boolean isTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof dev.langchain4j.exception.TimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** 描述实际生效的角色路由，避免输出地址、密钥或请求正文。 */
    private String modelRoute(ModelRole role) {
        String bean = properties.getModelByRole().get(role);
        if (StringUtils.hasText(bean)) return "bean=" + bean;
        String provider = properties.getProviderByRole().get(role);
        if (StringUtils.hasText(provider)) return "provider=" + provider.trim().toLowerCase(Locale.ROOT);
        return "bean=chatLanguageModel";
    }

    /** 请求结构化输出，首次解析失败时向同一角色发起一次 JSON 修复。 */
    public <T> T json(ModelRole role, String instruction, String input, Class<T> resultType) {
        String jsonInstruction = instruction + "\n只返回一个合法 JSON 对象，不要使用 Markdown 代码块，不要补充解释或思考过程。";
        ChatResponse response = generate(role, jsonInstruction, input, true);
        try {
            return parseJson(response.aiMessage().text(), resultType);
        } catch (JsonProcessingException | IllegalArgumentException firstError) {
            log.warn("角色 {} 首次返回的 JSON 无法解析，正在请求修复，{}，{}\n模型原始响应：\n{}\n解析错误：{}", role,
                    responseDetails(role, response), parseFailureDetails(firstError, resultType),
                    response.aiMessage().text(), firstError.getMessage(), firstError);
            String repairInput = "目标类型：" + resultType.getSimpleName()
                    + "\n原始字段及结构要求：\n" + instruction
                    + "\n原始输入数据：\n" + input
                    + "\n解析器错误：\n" + firstError.getMessage()
                    + "\n上次响应信息：\n" + responseDetails(role, response)
                    + "\n待修复内容：\n" + response.aiMessage().text();
            ChatResponse repaired = generate(role,
                    "根据原始输入、字段要求和解析器错误重新生成完整 JSON。上次内容可能缺失 JSON 或已被截断，"
                            + "此时应重新执行原始任务，不要凭空补写。字段类型必须完全匹配，内容保持简洁，"
                            + "只返回 JSON 对象，不要输出解释、Markdown 或思考过程。",
                    repairInput, true);
            try {
                return parseJson(repaired.aiMessage().text(), resultType);
            } catch (JsonProcessingException | IllegalArgumentException secondError) {
                log.error("角色 {} 修复后的 JSON 仍无法解析，{}，{}\n模型原始响应：\n{}\n解析错误：{}", role,
                        responseDetails(role, repaired), parseFailureDetails(secondError, resultType),
                        repaired.aiMessage().text(), secondError.getMessage(), secondError);
                String hint = response.finishReason() == FinishReason.LENGTH || repaired.finishReason() == FinishReason.LENGTH
                        ? "。模型输出达到 token 上限，请增加对应模型的 max-tokens 或缩短输入及分析内容。"
                        : "。请检查对应模型是否遵循 JSON 输出要求。";
                throw new WorkflowExecutionException("模型角色 " + role + " 未返回可解析的 JSON（首次："
                        + responseDetails(role, response) + "，" + parseFailureDetails(firstError, resultType)
                        + "；修复：" + responseDetails(role, repaired) + "，"
                        + parseFailureDetails(secondError, resultType) + "）" + hint,
                        secondError);
            }
        }
    }

    /** 记录解析失败的类型和位置，避免异常原文中的模型内容进入日志。 */
    private String parseFailureDetails(Exception error, Class<?> resultType) {
        String details = "targetType=" + resultType.getSimpleName()
                + ", errorType=" + error.getClass().getSimpleName();
        if (error instanceof JsonProcessingException jsonError && jsonError.getLocation() != null) {
            details += ", line=" + jsonError.getLocation().getLineNr()
                    + ", column=" + jsonError.getLocation().getColumnNr();
        }
        return details;
    }

    /** 只记录路由、模型和长度，不将原文或模型正文写入日志及错误提示。 */
    private String responseDetails(ModelRole role, ChatResponse response) {
        return modelRoute(role) + ", model=" + response.modelName() + ", finishReason=" + response.finishReason()
                + ", outputCharacters=" + response.aiMessage().text().length();
    }

    /** 将工作流上下文序列化为模型输入，失败时抛出工作流异常。 */
    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new WorkflowExecutionException("无法序列化工作流上下文", e);
        }
    }

    /** 提取 JSON 对象并按目标类型解析，支持将单值读作数组并忽略额外字段。 */
    private <T> T parseJson(String raw, Class<T> resultType) throws JsonProcessingException {
        String normalized = extractJsonObject(raw);
        return objectMapper.readerFor(resultType)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .readValue(normalized);
    }

    /** 去除 Markdown 围栏或前后说明，提取 JSON 对象范围。 */
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

    /** 按角色优先选择命名 Bean，其次使用配置供应商，最后使用默认模型。 */
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

    /** 构造指定供应商的角色模型，并将配置错误转换为工作流异常。 */
    private ChatModel buildProviderModel(String provider) {
        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        log.info("为改编工作流创建角色模型，provider={}", normalized);
        try {
            return modelFactory.create(normalized);
        } catch (IllegalArgumentException error) {
            String message = error.getMessage();
            if (message != null && message.startsWith("Unsupported chat provider: ")) {
                message = "工作流角色配置了不支持的 provider: " + provider;
            }
            throw new WorkflowExecutionException(message, error);
        }
    }
}
