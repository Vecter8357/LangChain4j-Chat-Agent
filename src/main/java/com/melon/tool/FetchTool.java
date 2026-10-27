package com.melon.tool;

import com.melon.config.properties.ToolSecurityProperties;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.melon.prompt.Langchain4jPrompt.MCP_RESULT;

/**
 * 网页内容获取工具
 * 使用uvx命令运行mcp-server-fetch
 */
@Component
@Slf4j
public class FetchTool {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s，。；、)）]+");

    private final ChatModel chatLanguageModel;

    private final ToolSecurityProperties.FetchProperties properties;

    @Autowired
    public FetchTool(ChatModel chatLanguageModel, ToolSecurityProperties toolSecurityProperties) {
        this.chatLanguageModel = chatLanguageModel;
        this.properties = toolSecurityProperties.getFetch();
        log.info("FetchTool初始化完成，timeout={}，debug={}", properties.getTimeout(), properties.isDebug());
    }

    /**
     * 获取网页内容并回答问题
     * @param question 用户问题
     * @return 回答
     */
    @Tool("获取网页内容并回答问题")
    public String fetchAndAnswer(String question) {
        log.info("收到获取网页内容请求: {}", question);

        if (!properties.isEnabled()) {
            return "网页获取工具已禁用";
        }

        String validationError = validateUrls(question);
        if (validationError != null) {
            return validationError;
        }

        McpClient mcpClient = null;
        try {
            // 创建MCP传输
            Map<String, String> env = new HashMap<>();
            env.put("PYTHONIOENCODING", "utf-8");

            // 使用更详细的命令配置
            McpTransport transport = new StdioMcpTransport.Builder()
                    .command(properties.getCommand())
                    .environment(env)
                    .logEvents(properties.isDebug())
                    .build();

            // 创建MCP客户端
            mcpClient = new DefaultMcpClient.Builder()
                    .transport(transport)
                    .build();

            // 创建工具提供者
            McpToolProvider toolProvider = McpToolProvider.builder()
                    .mcpClients(List.of(mcpClient))
                    .build();

            // 定义Bot接口
            FetchTool.FetchBot bot = AiServices.builder(FetchTool.FetchBot.class)
                    .chatModel(chatLanguageModel)
                    .toolProvider(toolProvider)
                    .build();
            try {
                // 调用Bot回答问题
                String response = CompletableFuture.supplyAsync(() -> bot.chat(question))
                        .get(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
                response = limitResponseLength(response);
                log.info("获取到网页问题回答，内容长度: {}", response.length());
                // 使用AI模型回答问题
                String answer = chatLanguageModel.chat(String.format(MCP_RESULT,response, question));

                log.info("AI回答: {}", answer);
                return answer;
            } catch (TimeoutException e) {
                log.error("调用Fetch MCP超时", e);
                return "网页获取失败: 调用超时，当前限制为 " + properties.getTimeout();
            } catch (Exception e) {
                log.error("调用Bot回答问题时发生错误", e);
                return "网页获取失败: " + e.getMessage() + "\n\n请确认URL可访问，并且目标域名在允许范围内。";
            }
        } catch (Exception e) {
            log.error("获取网页内容时发生错误", e);
            return "抱歉，获取网页内容时发生错误: " + e.getMessage();
        } finally {
            if (mcpClient != null) {
                try {
                    mcpClient.close();
                } catch (Exception e) {
                    log.debug("关闭Fetch MCP客户端失败", e);
                }
            }
        }
    }

    private String validateUrls(String question) {
        List<String> allowedDomains = properties.getAllowedDomains();
        if (allowedDomains == null || allowedDomains.stream().noneMatch(StringUtils::hasText)) {
            return null;
        }

        Matcher matcher = URL_PATTERN.matcher(question == null ? "" : question);
        boolean foundUrl = false;
        while (matcher.find()) {
            foundUrl = true;
            String url = matcher.group();
            try {
                String host = URI.create(url).getHost();
                if (!isAllowedDomain(host)) {
                    return "网页获取被拒绝：域名不在允许范围内。URL: " + url
                            + "，允许域名: " + String.join(", ", allowedDomains);
                }
            } catch (Exception e) {
                return "网页获取被拒绝：URL格式无效。URL: " + url;
            }
        }

        if (!foundUrl) {
            return "网页获取被拒绝：启用域名白名单时，问题中必须包含明确的 http/https URL";
        }
        return null;
    }

    private boolean isAllowedDomain(String host) {
        if (!StringUtils.hasText(host)) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return properties.getAllowedDomains().stream()
                .filter(StringUtils::hasText)
                .map(domain -> domain.toLowerCase(Locale.ROOT).trim())
                .anyMatch(domain -> normalizedHost.equals(domain) || normalizedHost.endsWith("." + domain));
    }

    private String limitResponseLength(String response) {
        if (response == null) {
            return "";
        }
        int maxResponseLength = properties.getMaxResponseLength();
        if (maxResponseLength <= 0 || response.length() <= maxResponseLength) {
            return response;
        }
        return response.substring(0, maxResponseLength) + "\n\n[内容过长，已按工具安全配置截断]";
    }

    /**
     * Fetch Bot接口
     */
    interface FetchBot {
        String chat(@UserMessage String message);
    }
}
