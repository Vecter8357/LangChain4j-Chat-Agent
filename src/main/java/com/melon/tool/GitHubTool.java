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

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * GitHub工具类，用于与GitHub交互
 */
@Slf4j
@Component
public class GitHubTool {

    private final ChatModel chatLanguageModel;

    private final ToolSecurityProperties.GitHubProperties properties;

    @Autowired
    public GitHubTool(ChatModel chatLanguageModel, ToolSecurityProperties toolSecurityProperties) {
        this.chatLanguageModel = chatLanguageModel;
        this.properties = toolSecurityProperties.getGithub();
    }
    
    /**
     * 向GitHub提出任意问题
     *
     * @param question 用户的问题
     * @return 回答
     */
    @Tool("向GitHub提出任意问题")
    public String askGitHubQuestion(String question) {
        log.info("向GitHub提出问题: {}", question);

        if (!properties.isEnabled()) {
            return "GitHub工具已禁用";
        }
        if (!StringUtils.hasText(properties.getToken())) {
            return "GitHub工具未配置Token，请设置 tool-security.github.token 或 GITHUB_TOKEN";
        }
        if (!isAllowedRepositoryQuestion(question)) {
            return "GitHub工具访问被拒绝：问题未命中允许的仓库范围。允许仓库: "
                    + String.join(", ", properties.getAllowedRepositories());
        }

        try {
            // 创建MCP传输
            Map<String, String> env = new HashMap<>();
            env.put("GITHUB_PERSONAL_ACCESS_TOKEN", properties.getToken());
            
            log.info("正在创建MCP传输，命令: {}", properties.getCommand());
            
            // 使用更详细的命令配置
            McpTransport transport = new StdioMcpTransport.Builder()
                .command(properties.getCommand())
                .environment(env)
                .logEvents(true)
                .build();
            
            // 创建MCP客户端
            McpClient mcpClient = new DefaultMcpClient.Builder()
                .transport(transport)
                .build();
            
            // 创建工具提供者
            McpToolProvider toolProvider = McpToolProvider.builder()
                .mcpClients(List.of(mcpClient))
                .build();
            
            // 定义Bot接口
            GitHubBot bot = AiServices.builder(GitHubBot.class)
                .chatModel(chatLanguageModel)
                .toolProvider(toolProvider)
                .build();
            try {
                // 调用Bot回答问题
                String response = CompletableFuture.supplyAsync(() -> bot.chat(question))
                        .get(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
                log.info("获取到GitHub问题回答: {}", response);
                return response;
            } catch (TimeoutException e) {
                log.error("调用GitHub MCP超时", e);
                return "向GitHub提问失败: 调用超时，当前限制为 " + properties.getTimeout();
            } catch (Exception e) {
                log.error("调用Bot回答问题时发生错误", e);
                return "向GitHub提问失败: " + e.getMessage() + "\n\n请确保您的问题与GitHub相关，并且GitHub令牌有足够的权限。";
            } finally {
                log.info("关闭MCP客户端");
                mcpClient.close();
            }
        } catch (Exception e) {
            log.error("向GitHub提问失败", e);
            return "向GitHub提问失败: " + e.getMessage() + "\n\n可能的原因：\n1. Node.js或npm未正确安装\n2. GitHub令牌无效或权限不足\n3. 网络连接问题\n4. @modelcontextprotocol/server-github包安装失败";
        }
    }

    private boolean isAllowedRepositoryQuestion(String question) {
        List<String> allowedRepositories = properties.getAllowedRepositories();
        if (allowedRepositories == null || allowedRepositories.stream().noneMatch(StringUtils::hasText)) {
            return true;
        }
        if (!StringUtils.hasText(question)) {
            return false;
        }

        String normalizedQuestion = question.toLowerCase(Locale.ROOT);
        return allowedRepositories.stream()
                .filter(StringUtils::hasText)
                .map(repository -> repository.toLowerCase(Locale.ROOT).trim())
                .anyMatch(normalizedQuestion::contains);
    }

    /**
     * 向GitHub提出任意问题（别名方法，与askGitHubQuestion功能相同）
     *
     * @param question 用户的问题
     * @return 回答
     */
    public String askGitHub(String question) {
        return askGitHubQuestion(question);
    }

    /**
     * GitHub Bot接口
     */
    interface GitHubBot {
        String chat(@UserMessage String message);
    }
}
