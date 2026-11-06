package com.melon.mcp;

import com.melon.agent.capability.CapabilityDescriptor;
import com.melon.agent.capability.CapabilityProvisioningService;
import com.melon.agent.runtime.AgentDefinition;
import com.melon.agent.runtime.AgentRun;
import com.melon.agent.runtime.AgentRunRequest;
import com.melon.agent.runtime.AgentRuntime;
import com.melon.config.properties.AgentRuntimeProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;

@Service
public class McpAgentRunner {

    private final AgentRuntime runtime;
    private final CapabilityProvisioningService provisioning;
    private final AgentRuntimeProperties properties;

    /** 装配 MCP 能力发现、Agent Runtime 和能力数量限制配置。 */
    public McpAgentRunner(AgentRuntime runtime, CapabilityProvisioningService provisioning,
                          AgentRuntimeProperties properties) {
        this.runtime = runtime;
        this.provisioning = provisioning;
        this.properties = properties;
    }

    /** Runs an Agent with capabilities discovered for the given MCP providers. */
    public String answer(String agentId, String systemPrompt, String objective, List<String> providerIds) {
        List<CapabilityDescriptor> capabilities = provisioning.ensureMcpProviders(providerIds);
        if (!providerIds.isEmpty() && capabilities.isEmpty()) {
            return "Agent 执行失败: 没有发现可用的 MCP 能力: " + providerIds;
        }
        int maximum = properties.getMaximumCapabilities();
        if (capabilities.size() > maximum) {
            throw new IllegalArgumentException("可用能力数量 " + capabilities.size() + " 超过 Agent Runtime 上限 " + maximum);
        }
        AgentDefinition definition = new AgentDefinition();
        definition.setId(agentId);
        definition.setName(agentId);
        definition.setSystemPrompt(systemPrompt);
        var ids = new LinkedHashSet<String>();
        capabilities.forEach(capability -> ids.add(capability.id()));
        definition.setCapabilityIds(ids);

        AgentRunRequest request = new AgentRunRequest();
        request.setDefinition(definition);
        request.setObjective(objective);
        request.setInput(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
        AgentRun run = runtime.execute(request);
        if (run.getStatus() == AgentRun.Status.COMPLETED && StringUtils.hasText(run.getFinalAnswer())) {
            return run.getFinalAnswer();
        }
        return "Agent 执行失败: " + (StringUtils.hasText(run.getFailureReason())
                ? run.getFailureReason() : run.getStatus().name());
    }
}
