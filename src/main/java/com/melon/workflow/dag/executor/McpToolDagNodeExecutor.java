package com.melon.workflow.dag.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.workflow.McpResearchGateway;
import com.melon.workflow.dag.model.DagWorkflowDefinition.NodeType;
import com.melon.workflow.model.RewriteWorkflowState.ResearchEvidence;
import com.melon.workflow.model.RewriteWorkflowState.ResearchPlanItem;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;

@Component
public class McpToolDagNodeExecutor implements DagNodeExecutor {

    private final McpResearchGateway researchGateway;
    private final ObjectMapper objectMapper;

    public McpToolDagNodeExecutor(McpResearchGateway researchGateway, ObjectMapper objectMapper) {
        this.researchGateway = researchGateway;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<NodeType> supportedTypes() {
        return Set.of(NodeType.MCP_TOOL);
    }

    @Override
    public DagNodeExecutionResult execute(DagNodeExecutionContext context) {
        JsonNode config = context.node().getConfig();
        String operation = config == null ? "research" : config.path("operation").asText("research");
        if (!"research".equalsIgnoreCase(operation)) {
            throw new IllegalArgumentException("MCP_TOOL MVP 当前只支持 research 操作: " + operation);
        }

        String question = text(context.input(), "question", config);
        if (!StringUtils.hasText(question)) {
            throw new IllegalArgumentException("MCP research 节点必须提供 question");
        }
        ResearchPlanItem item = new ResearchPlanItem();
        item.setQuestion(question);
        item.setPurpose(text(context.input(), "purpose", config));
        item.setUrl(text(context.input(), "url", config));
        ResearchEvidence evidence = researchGateway.research(item);
        return DagNodeExecutionResult.success(objectMapper.valueToTree(evidence));
    }

    private String text(JsonNode input, String field, JsonNode config) {
        JsonNode inputValue = input == null ? null : input.get(field);
        if (inputValue != null && !inputValue.isNull()) {
            return inputValue.asText();
        }
        return config == null ? null : config.path(field).asText(null);
    }
}
