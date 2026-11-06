package com.melon.config;

import com.melon.config.properties.McpProperties.McpServerProperties;
import com.melon.config.properties.RewriteWorkflowProperties;
import com.melon.mcp.McpServerDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class McpCompatibilityConfig {
    /** 将启用且已配置命令的改编搜索映射为 MCP 默认服务，显式 MCP 配置可覆盖该条目。 */
    @Bean
    McpServerDefaults rewriteSearchMcpDefaults(RewriteWorkflowProperties properties) {
        return () -> {
            var source = properties.getResearch();
            if (!source.isSearchEnabled() || source.getSearchCommand() == null || source.getSearchCommand().isEmpty()) {
                return Map.of();
            }
            var target = new McpServerProperties();
            target.setCommand(source.getSearchCommand());
            target.setEnvironment(source.getEnvironment());
            target.setTimeout(source.getTimeout());
            target.setDebug(source.isDebug());
            return Map.of("rewrite-search", target);
        };
    }
}
