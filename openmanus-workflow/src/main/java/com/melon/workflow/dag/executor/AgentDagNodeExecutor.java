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
    private final GeneralAgentRunner generalAgent;

    /**
     * 创建 Agent 节点执行器。
     */
    public AgentDagNodeExecutor(AgentRuntime agentRuntime, ObjectMapper objectMapper, GeneralAgentRunner generalAgent) {
        this.agentRuntime = agentRuntime;
        this.objectMapper = objectMapper;
        this.generalAgent = generalAgent;
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
        String agentRef = config == null ? null : config.path("agentRef").asText(null);
        String objective = context.input() == null ? null : context.input().path("objective").asText(null);
        if (!StringUtils.hasText(objective)) {
            objective = config == null ? null : config.path("objective").asText(null);
        }
        if (!StringUtils.hasText(objective)) {
            throw new IllegalArgumentException("AGENT 节点必须通过输入或 config 提供 objective");
        }

        if (StringUtils.hasText(agentRef)) {
            if (!GeneralAgentRunner.AGENT_ID.equals(agentRef)) {
                throw new IllegalArgumentException("未知 Agent 引用: " + agentRef);
            }
            return completed(generalAgent.run(objective, context.input()));
        }
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

        AgentRunRequest request = new AgentRunRequest();
        request.setDefinition(definition);
        request.setObjective(objective);
        request.setInput(context.input());
        return completed(agentRuntime.execute(request));
    }

    /** 仅将已完成的 Agent Run 转换为节点输出，其他状态报告节点失败。 */
    private DagNodeExecutionResult completed(AgentRun run) {
        if (run.getStatus() != AgentRun.Status.COMPLETED) {
            throw new IllegalStateException("Agent 执行未完成: " + run.getStatus() + " - " + run.getFailureReason());
        }
        return DagNodeExecutionResult.success(objectMapper.valueToTree(run));
    }
}
