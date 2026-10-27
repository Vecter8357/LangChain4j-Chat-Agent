package com.melon.workflow.dag.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.melon.workflow.WorkflowModelGateway;
import com.melon.workflow.dag.model.DagWorkflowDefinition.NodeType;
import com.melon.workflow.model.ModelRole;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

@Component
public class LlmDagNodeExecutor implements DagNodeExecutor {

    private final WorkflowModelGateway modelGateway;
    private final ObjectMapper objectMapper;

    public LlmDagNodeExecutor(WorkflowModelGateway modelGateway, ObjectMapper objectMapper) {
        this.modelGateway = modelGateway;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<NodeType> supportedTypes() {
        return Set.of(NodeType.LLM);
    }

    @Override
    public DagNodeExecutionResult execute(DagNodeExecutionContext context) {
        JsonNode config = context.node().getConfig();
        if (config == null || config.isNull()) {
            throw new IllegalArgumentException("LLM 节点未配置 config: " + context.node().getId());
        }
        String roleName = config.path("role").asText();
        String instruction = config.path("instruction").asText();
        if (!StringUtils.hasText(roleName) || !StringUtils.hasText(instruction)) {
            throw new IllegalArgumentException("LLM 节点必须配置 role 和 instruction: " + context.node().getId());
        }

        ModelRole role;
        try {
            role = ModelRole.valueOf(roleName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("LLM 节点配置了未知角色: " + roleName, error);
        }

        String responseMode = config.path("responseMode").asText("TEXT");
        JsonNode output;
        if ("JSON".equalsIgnoreCase(responseMode) || "JSON_SCHEMA".equalsIgnoreCase(responseMode)) {
            output = modelGateway.json(role, instruction, context.input().toString(), JsonNode.class);
        } else {
            String text = modelGateway.text(role, instruction, context.input().toString());
            ObjectNode result = objectMapper.createObjectNode();
            result.put("text", text);
            output = result;
        }
        return DagNodeExecutionResult.success(output);
    }
}
