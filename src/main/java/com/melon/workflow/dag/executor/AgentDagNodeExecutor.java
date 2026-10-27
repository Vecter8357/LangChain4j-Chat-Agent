package com.melon.workflow.dag.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.agent.runtime.AgentDefinition;
import com.melon.agent.runtime.AgentRun;
import com.melon.agent.runtime.AgentRunRequest;
import com.melon.agent.runtime.AgentRuntime;
import com.melon.workflow.dag.model.DagWorkflowDefinition.NodeType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * DAG 的 AGENT 节点执行器，把节点配置和上游输入转换为 AgentRunRequest。
 */
@Component
public class AgentDagNodeExecutor implements DagNodeExecutor {

    private final AgentRuntime agentRuntime;
    private final ObjectMapper objectMapper;

    /**
     * 创建 Agent 节点执行器。
     */
    public AgentDagNodeExecutor(AgentRuntime agentRuntime, ObjectMapper objectMapper) {
        this.agentRuntime = agentRuntime;
        this.objectMapper = objectMapper;
    }

    /** {@inheritDoc} */
    @Override
    public Set<NodeType> supportedTypes() {
        return Set.of(NodeType.AGENT);
    }

    /**
     * {@inheritDoc}
     * Agent 未完成时将节点视为失败，避免 DAG 使用不完整输出继续运行。
     */
    @Override
    public DagNodeExecutionResult execute(DagNodeExecutionContext context) {
        JsonNode config = context.node().getConfig();
        if (config == null || !StringUtils.hasText(config.path("systemPrompt").asText())) {
            throw new IllegalArgumentException("AGENT 节点必须配置 config.systemPrompt");
        }

        AgentDefinition definition = new AgentDefinition();
        definition.setId(config.path("agentId").asText(context.node().getId()));
        definition.setName(config.path("name").asText(context.node().getName()));
        definition.setSystemPrompt(config.path("systemPrompt").asText());
        definition.setMaxSteps(config.path("maxSteps").asInt(0));
        LinkedHashSet<String> capabilityIds = new LinkedHashSet<>();
        JsonNode configuredCapabilities = config.path("capabilityIds");
        if (configuredCapabilities.isArray()) {
            configuredCapabilities.forEach(item -> capabilityIds.add(item.asText()));
        }
        definition.setCapabilityIds(capabilityIds);

        String objective = context.input() == null ? null : context.input().path("objective").asText(null);
        if (!StringUtils.hasText(objective)) {
            objective = config.path("objective").asText(null);
        }
        if (!StringUtils.hasText(objective)) {
            throw new IllegalArgumentException("AGENT 节点必须通过输入或 config 提供 objective");
        }

        AgentRunRequest request = new AgentRunRequest();
        request.setDefinition(definition);
        request.setObjective(objective);
        request.setInput(context.input());
        AgentRun run = agentRuntime.execute(request);
        if (run.getStatus() != AgentRun.Status.COMPLETED) {
            throw new IllegalStateException("Agent 执行未完成: " + run.getStatus() + " - " + run.getFailureReason());
        }
        return DagNodeExecutionResult.success(objectMapper.valueToTree(run));
    }
}
