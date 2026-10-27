package com.melon.workflow;

import com.melon.config.properties.RewriteWorkflowProperties;
import com.melon.tool.FetchTool;
import com.melon.workflow.model.RewriteWorkflowState.ResearchEvidence;
import com.melon.workflow.model.RewriteWorkflowState.ResearchPlanItem;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 工作流与 MCP 网络工具之间的边界。模型负责规划问题，工作流负责校验并调用工具。
 */
@Slf4j
@Component
public class McpResearchGateway {

    private final FetchTool fetchTool;
    private final ChatModel chatLanguageModel;
    private final RewriteWorkflowProperties properties;

    public McpResearchGateway(FetchTool fetchTool,
                              ChatModel chatLanguageModel,
                              RewriteWorkflowProperties properties) {
        this.fetchTool = fetchTool;
        this.chatLanguageModel = chatLanguageModel;
        this.properties = properties;
    }

    public ResearchEvidence research(ResearchPlanItem item) {
        ResearchEvidence evidence = new ResearchEvidence();
        evidence.setPurpose(item.getPurpose());
        evidence.setQuestion(item.getQuestion());
        evidence.setUrl(item.getUrl());
        evidence.setRetrievedAt(Instant.now());

        if (properties.getResearch().isSearchEnabled()) {
            String searchResult = searchViaConfiguredMcp(item);
            if (StringUtils.hasText(searchResult) && !looksLikeToolError(searchResult)) {
                evidence.setContent(searchResult);
                evidence.setSuccessful(true);
                return evidence;
            }
            if (!isHttpUrl(item.getUrl())) {
                evidence.setContent(searchResult);
                evidence.setSuccessful(false);
                return evidence;
            }
        }

        if (!isHttpUrl(item.getUrl())) {
            evidence.setSuccessful(false);
            evidence.setContent("研究任务被拒绝：未配置 MCP 搜索服务，并且缺少合法的 http/https URL");
            return evidence;
        }

        String request = "请读取并分析以下网页，仅提取与研究问题直接相关且网页能够支持的信息。"
                + "不要补充网页中不存在的事实。\nURL: " + item.getUrl()
                + "\n研究目的: " + item.getPurpose()
                + "\n研究问题: " + item.getQuestion();
        String content = fetchTool.fetchAndAnswer(request);
        evidence.setContent(content);
        evidence.setSuccessful(!looksLikeToolError(content));
        return evidence;
    }

    private String searchViaConfiguredMcp(ResearchPlanItem item) {
        if (properties.getResearch().getSearchCommand() == null
                || properties.getResearch().getSearchCommand().isEmpty()) {
            return "MCP 搜索失败：search-enabled=true，但 search-command 未配置";
        }

        McpClient client = null;
        try {
            McpTransport transport = new StdioMcpTransport.Builder()
                    .command(properties.getResearch().getSearchCommand())
                    .environment(properties.getResearch().getEnvironment())
                    .logEvents(properties.getResearch().isDebug())
                    .build();
            client = new DefaultMcpClient.Builder()
                    .transport(transport)
                    .toolExecutionTimeout(properties.getResearch().getTimeout())
                    .build();
            McpToolProvider toolProvider = McpToolProvider.builder()
                    .mcpClients(List.of(client))
                    .failIfOneServerFails(true)
                    .build();
            SearchAssistant assistant = AiServices.builder(SearchAssistant.class)
                    .chatModel(chatLanguageModel)
                    .toolProvider(toolProvider)
                    .build();
            String prompt = "使用 MCP 搜索工具研究下面的问题。至少交叉核对两个结果；"
                    + "回答时给出来源标题和 URL，不得把未经来源支持的内容写成事实。\n"
                    + "研究目的: " + item.getPurpose() + "\n研究问题: " + item.getQuestion();
            return CompletableFuture.supplyAsync(() -> assistant.research(prompt))
                    .get(properties.getResearch().getTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("调用工作流 MCP 搜索服务失败", e);
            return "MCP 搜索失败：" + rootMessage(e);
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception e) {
                    log.debug("关闭工作流 MCP 搜索客户端失败", e);
                }
            }
        }
    }

    private boolean isHttpUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.startsWith("http://") || normalized.startsWith("https://");
    }

    private boolean looksLikeToolError(String content) {
        if (!StringUtils.hasText(content)) {
            return true;
        }
        String normalized = content.trim().toLowerCase();
        return normalized.startsWith("错误")
                || normalized.startsWith("网页获取失败")
                || normalized.startsWith("网页获取被拒绝")
                || normalized.startsWith("mcp 搜索失败")
                || normalized.startsWith("抱歉");
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    interface SearchAssistant {
        String research(@UserMessage String message);
    }
}
