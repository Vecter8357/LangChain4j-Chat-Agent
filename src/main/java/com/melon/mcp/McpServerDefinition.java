package com.melon.mcp;

import com.melon.config.properties.AgentPlatformProperties.McpServerProperties;

/**
 * MCP Server 的已解析定义。
 *
 * @param id 服务唯一标识
 * @param configuration 连接与超时配置
 */
public record McpServerDefinition(String id, McpServerProperties configuration) {
}
