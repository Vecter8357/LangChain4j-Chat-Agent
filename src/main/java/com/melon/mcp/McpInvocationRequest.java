package com.melon.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 统一 MCP Tool 调用请求。
 *
 * @param runId 所属 Agent 或 Workflow Run
 * @param agentId 调用方 Agent 标识
 * @param capabilityId Capability Registry 中的能力 ID
 * @param arguments 工具参数
 */
public record McpInvocationRequest(
        String runId,
        String agentId,
        String capabilityId,
        JsonNode arguments
) {
}
