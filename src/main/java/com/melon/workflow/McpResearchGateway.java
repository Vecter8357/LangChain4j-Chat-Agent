package com.melon.workflow;

import com.melon.config.properties.RewriteWorkflowProperties;
import com.melon.agent.runtime.AgentRun;
import com.melon.agent.runtime.PlatformAgentService;
import com.melon.tool.FetchTool;
import com.melon.workflow.model.RewriteWorkflowState.ResearchEvidence;
import com.melon.workflow.model.RewriteWorkflowState.ResearchPlanItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;

/**
 * 改编工作流的研究边界。
 * 搜索任务通过统一 Agent/MCP 平台执行，明确 URL 则通过 Fetch 兼容入口执行。
 */
@Slf4j
@Component
public class McpResearchGateway {

    private final FetchTool fetchTool;
    private final PlatformAgentService platformAgentService;
    private final RewriteWorkflowProperties properties;

    /**
     * 创建改编研究网关。
     */
    public McpResearchGateway(FetchTool fetchTool,
                              PlatformAgentService platformAgentService,
                              RewriteWorkflowProperties properties) {
        this.fetchTool = fetchTool;
        this.platformAgentService = platformAgentService;
        this.properties = properties;
    }

    /**
     * 根据研究计划优先执行 MCP 搜索，必要时回退到指定 URL 抓取。
     *
     * @param item 研究问题、目的和可选 URL
     * @return 带时间和成功状态的研究证据
     */
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

    /**
     * 使用 rewrite-search 提供方的能力运行资料研究 Agent。
     */
    private String searchViaConfiguredMcp(ResearchPlanItem item) {
        if (properties.getResearch().getSearchCommand() == null
                || properties.getResearch().getSearchCommand().isEmpty()) {
            return "MCP 搜索失败：search-enabled=true，但 search-command 未配置";
        }

        try {
            String prompt = "使用 MCP 搜索工具研究下面的问题。至少交叉核对两个结果；"
                    + "回答时给出来源标题和 URL，不得把未经来源支持的内容写成事实。\n"
                    + "研究目的: " + item.getPurpose() + "\n研究问题: " + item.getQuestion();
            AgentRun run = platformAgentService.runWithProviders(
                    "rewrite-research-agent",
                    "你是文章改编工作流的资料研究 Agent。必须通过搜索能力取得证据并提供来源。",
                    prompt,
                    List.of("rewrite-search"),
                    List.of());
            return platformAgentService.answer(run);
        } catch (Exception e) {
            log.error("调用工作流 MCP 搜索服务失败", e);
            return "MCP 搜索失败：" + rootMessage(e);
        }
    }

    /**
     * 判断字符串是否为允许的 HTTP/HTTPS URL。
     */
    private boolean isHttpUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.startsWith("http://") || normalized.startsWith("https://");
    }

    /**
     * 识别兼容入口返回的常见工具错误文本。
     */
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

    /**
     * 提取异常链最底层信息。
     */
    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
