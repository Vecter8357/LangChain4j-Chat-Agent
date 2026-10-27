package com.melon.mcp;

import com.melon.agent.capability.CapabilityDescriptor;

import java.util.List;

/**
 * MCP 能力发现与调用的统一业务入口。
 */
public interface McpInvocationService {

    /**
     * 读取指定 MCP Server 的 Tool 列表并同步到 Capability Registry。
     *
     * @param serverId MCP Server 标识
     * @return 本次发现的能力
     */
    List<CapabilityDescriptor> refreshCapabilities(String serverId);

    /**
     * 校验并执行一个已注册 MCP Tool。
     *
     * @param request 调用请求
     * @return 标准化调用结果
     */
    McpInvocationResult invoke(McpInvocationRequest request);
}
