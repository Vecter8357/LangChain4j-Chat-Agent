package com.melon.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 统一 MCP Tool 调用请求。
 *
 * @param runId 所属 Agent 或 Workflow Run
 * @param agentId 调用方 Agent 标识
 * @param capabilityId Capability Registry 中的能力 ID
 * @param arguments 工具参数
 * @param toolCallId 模型调用 ID，直接调用时可为空
 */
public record McpInvocationRequest(
        String runId,
        String agentId,
        String capabilityId,
        JsonNode arguments,
        String toolCallId
) {
    /** 保持原有四参数调用方式，直接调用时仍由 MCP 层生成 ID。 */
    public McpInvocationRequest(String runId, String agentId, String capabilityId, JsonNode arguments) {
        this(runId, agentId, capabilityId, arguments, null);
    }
}
