package com.melon.mcp;

import dev.langchain4j.mcp.client.McpClient;

/**
 * MCP Client 生命周期管理器，负责连接复用、健康检查和失效重建。
 */
public interface McpConnectionManager {

    /**
     * 获取或创建指定服务的 MCP Client。
     *
     * @param serverId MCP Server 标识
     * @return 可复用客户端
     */
    McpClient get(String serverId);

    /**
     * 使指定连接失效并关闭客户端。
     *
     * @param serverId MCP Server 标识
     */
    void invalidate(String serverId);

    /**
     * 检查连接健康状态，失败时自动清理连接。
     *
     * @param serverId MCP Server 标识
     */
    void checkHealth(String serverId);
}
