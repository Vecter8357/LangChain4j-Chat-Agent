package com.melon.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.melon.agent.capability.CapabilityDescriptor;

/**
 * 通用能力调用请求与结果模型的命名空间。
 */
public final class CapabilityInvocation {

    /**
     * 工具类不允许实例化。
     */
    private CapabilityInvocation() {
    }

    /**
     * 通用能力调用请求。
     *
     * @param runId Agent Run 标识
     * @param agentId Agent 标识
     * @param capability 被调用的能力描述
     * @param arguments 已解析的 JSON 参数
     */
    public record Request(
            String runId,
            String agentId,
            CapabilityDescriptor capability,
            JsonNode arguments
    ) {
    }

    /**
     * 通用能力调用结果。
     *
     * @param successful 是否成功
     * @param content 标准化结果
     * @param errorCode 错误代码
     * @param errorMessage 错误说明
     * @param retryable 是否适合重试
     * @param durationMillis 调用耗时
     */
    public record Result(
            boolean successful,
            JsonNode content,
            String errorCode,
            String errorMessage,
            boolean retryable,
            long durationMillis
    ) {
    }
}
