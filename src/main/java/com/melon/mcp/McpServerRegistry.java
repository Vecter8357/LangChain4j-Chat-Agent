package com.melon.mcp;

import com.melon.config.properties.AgentPlatformProperties;
import com.melon.config.properties.AgentPlatformProperties.McpServerProperties;
import com.melon.config.properties.RewriteWorkflowProperties;
import com.melon.config.properties.ToolSecurityProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Server 配置注册表。
 * 合并平台显式配置与旧 GitHub、Fetch、改编搜索配置，显式配置具有更高优先级。
 */
@Component
public class McpServerRegistry {

    private final AgentPlatformProperties properties;
    private final ToolSecurityProperties toolSecurityProperties;
    private final RewriteWorkflowProperties rewriteWorkflowProperties;

    /**
     * 创建 MCP Server 注册表。
     */
    public McpServerRegistry(AgentPlatformProperties properties,
                             ToolSecurityProperties toolSecurityProperties,
                             RewriteWorkflowProperties rewriteWorkflowProperties) {
        this.properties = properties;
        this.toolSecurityProperties = toolSecurityProperties;
        this.rewriteWorkflowProperties = rewriteWorkflowProperties;
    }

    /**
     * 获取一个已配置且已启用的 MCP Server。
     *
     * @param serverId 服务标识
     * @return 服务定义
     */
    public McpServerDefinition require(String serverId) {
        if (!StringUtils.hasText(serverId)) {
            throw new IllegalArgumentException("MCP serverId 不能为空");
        }
        var configuration = definitions().get(serverId);
        if (configuration == null) {
            throw new IllegalArgumentException("未配置 MCP Server: " + serverId);
        }
        if (!configuration.isEnabled()) {
            throw new IllegalStateException("MCP Server 已禁用: " + serverId);
        }
        return new McpServerDefinition(serverId, configuration);
    }

    /**
     * 返回全部已启用服务定义。
     */
    public List<McpServerDefinition> listEnabled() {
        return definitions().entrySet().stream()
                .filter(entry -> entry.getValue().isEnabled())
                .map(entry -> new McpServerDefinition(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * 每次读取时合并兼容配置和平台配置，避免在注册表中暴露可变内部状态。
     */
    private Map<String, McpServerProperties> definitions() {
        Map<String, McpServerProperties> definitions = new LinkedHashMap<>();
        addLegacyGitHub(definitions);
        addLegacyFetch(definitions);
        addLegacyRewriteSearch(definitions);
        definitions.putAll(properties.getMcp().getServers());
        return definitions;
    }

    /**
     * 把旧 GitHub Tool 配置映射为名为 github 的 stdio MCP Server。
     */
    private void addLegacyGitHub(Map<String, McpServerProperties> definitions) {
        var source = toolSecurityProperties.getGithub();
        if (!source.isEnabled() || !StringUtils.hasText(source.getToken())) {
            return;
        }
        McpServerProperties target = new McpServerProperties();
        target.setCommand(source.getCommand());
        target.setTimeout(source.getTimeout());
        target.setEnvironment(Map.of("GITHUB_PERSONAL_ACCESS_TOKEN", source.getToken()));
        definitions.put("github", target);
    }

    /**
     * 把旧 Fetch Tool 配置映射为名为 fetch 的 stdio MCP Server。
     */
    private void addLegacyFetch(Map<String, McpServerProperties> definitions) {
        var source = toolSecurityProperties.getFetch();
        if (!source.isEnabled()) {
            return;
        }
        McpServerProperties target = new McpServerProperties();
        target.setCommand(source.getCommand());
        target.setTimeout(source.getTimeout());
        target.setDebug(source.isDebug());
        target.setEnvironment(Map.of("PYTHONIOENCODING", "utf-8"));
        definitions.put("fetch", target);
    }

    /**
     * 把改编工作流搜索命令映射为名为 rewrite-search 的 MCP Server。
     */
    private void addLegacyRewriteSearch(Map<String, McpServerProperties> definitions) {
        var source = rewriteWorkflowProperties.getResearch();
        if (!source.isSearchEnabled() || source.getSearchCommand() == null || source.getSearchCommand().isEmpty()) {
            return;
        }
        McpServerProperties target = new McpServerProperties();
        target.setCommand(source.getSearchCommand());
        target.setEnvironment(source.getEnvironment());
        target.setTimeout(source.getTimeout());
        target.setDebug(source.isDebug());
        definitions.put("rewrite-search", target);
    }
}
