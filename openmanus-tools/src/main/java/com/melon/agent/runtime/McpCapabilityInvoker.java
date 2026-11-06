package com.melon.agent.runtime;

import com.melon.agent.capability.CapabilityType;
import com.melon.mcp.McpInvocationRequest;
import com.melon.mcp.McpInvocationResult;
import com.melon.mcp.McpInvocationService;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 把通用 Capability 调用适配为 MCP Invocation Service 请求。
 */
@Component
public class McpCapabilityInvoker implements CapabilityInvoker {

    private final McpInvocationService invocationService;

    /**
     * 创建 MCP 能力调用器。
     */
    public McpCapabilityInvoker(McpInvocationService invocationService) {
        this.invocationService = invocationService;
    }

    /** {@inheritDoc} */
    @Override
    public Set<CapabilityType> supportedTypes() {
        return Set.of(CapabilityType.MCP_TOOL);
    }

    /** {@inheritDoc} */
    @Override
    public CapabilityInvocation.Result invoke(CapabilityInvocation.Request request) {
        McpInvocationResult result = invocationService.invoke(new McpInvocationRequest(
                request.runId(), request.agentId(), request.capability().id(), request.arguments()));
        return new CapabilityInvocation.Result(result.successful(), result.content(), result.errorCode(),
                result.errorMessage(), result.retryable(), result.durationMillis());
    }
}
