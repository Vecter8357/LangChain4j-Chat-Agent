package com.melon.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 统一 MCP Tool 调用结果。
 */
public record McpInvocationResult(
        boolean successful,
        String capabilityId,
        String serverId,
        String toolName,
        JsonNode content,
        String errorCode,
        String errorMessage,
        boolean retryable,
        long durationMillis
) {
    /**
     * 创建成功结果。
     */
    public static McpInvocationResult success(String capabilityId,
                                              String serverId,
                                              String toolName,
                                              JsonNode content,
                                              long durationMillis) {
        return new McpInvocationResult(true, capabilityId, serverId, toolName, content,
                null, null, false, durationMillis);
    }

    /**
     * 创建失败结果。
     */
    public static McpInvocationResult failure(String capabilityId,
                                              String serverId,
                                              String toolName,
                                              String errorCode,
                                              String errorMessage,
                                              boolean retryable,
                                              long durationMillis) {
        return new McpInvocationResult(false, capabilityId, serverId, toolName, null,
                errorCode, errorMessage, retryable, durationMillis);
    }
}
