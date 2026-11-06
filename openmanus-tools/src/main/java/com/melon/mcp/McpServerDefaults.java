package com.melon.mcp;

import com.melon.config.properties.McpProperties.McpServerProperties;

import java.util.Map;

/** Compatibility defaults that explicit platform MCP configuration can override. */
@FunctionalInterface
public interface McpServerDefaults {
    /** 提供可由显式平台配置覆盖的 MCP 服务定义。 */
    Map<String, McpServerProperties> servers();
}
