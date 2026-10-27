package com.melon.tool;

import com.melon.agent.runtime.AgentRun;
import com.melon.agent.runtime.PlatformAgentService;
import com.melon.config.properties.ToolSecurityProperties;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * GitHub 兼容入口。实际模型决策、MCP 能力发现和调用均由统一 Agent 平台完成。
 */
@Slf4j
@Component
public class GitHubTool {

    private static final String SYSTEM_PROMPT = """
            你是 GitHub 研究 Agent。必须使用已提供的 GitHub MCP 能力查询仓库、代码、提交或 Issue，
            不得虚构 GitHub 中不存在的信息。回答中说明使用了哪些查询结果。
            """;

    private final PlatformAgentService platformAgentService;
    private final ToolSecurityProperties.GitHubProperties properties;

    /**
     * 创建 GitHub 兼容工具入口。
     */
    public GitHubTool(PlatformAgentService platformAgentService,
                      ToolSecurityProperties toolSecurityProperties) {
        this.platformAgentService = platformAgentService;
        this.properties = toolSecurityProperties.getGithub();
    }

    /**
     * 校验 GitHub 开关、令牌和仓库范围后，通过统一 Agent Runtime 完成查询。
     *
     * @param question 用户问题
     * @return GitHub Agent 回答或安全错误
     */
    @Tool("向GitHub提出任意问题")
    public String askGitHubQuestion(String question) {
        log.info("通过统一 Agent Runtime 处理 GitHub 问题: {}", question);
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
        AgentRun run = platformAgentService.runWithProviders(
                "github-agent", SYSTEM_PROMPT, question, List.of("github"), List.of());
        return platformAgentService.answer(run);
    }

    /**
     * {@link #askGitHubQuestion(String)} 的兼容别名。
     */
    public String askGitHub(String question) {
        return askGitHubQuestion(question);
    }

    /**
     * 在进入 Agent 前检查问题是否明确命中配置的仓库白名单。
     */
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
}
