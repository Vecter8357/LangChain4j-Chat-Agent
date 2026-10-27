package com.melon.workflow.dag.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.workflow.McpResearchGateway;
import com.melon.mcp.McpInvocationRequest;
import com.melon.mcp.McpInvocationResult;
import com.melon.mcp.McpInvocationService;
import com.melon.workflow.dag.model.DagWorkflowDefinition.NodeType;
import com.melon.workflow.model.RewriteWorkflowState.ResearchEvidence;
import com.melon.workflow.model.RewriteWorkflowState.ResearchPlanItem;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * DAG 的 MCP_TOOL 节点执行器。
 * 优先按 capabilityId 使用统一 MCP 服务，同时兼容旧 research 操作。
 */
@Component
public class McpToolDagNodeExecutor implements DagNodeExecutor {

    private final McpResearchGateway researchGateway;
    private final McpInvocationService invocationService;
    private final ObjectMapper objectMapper;

    /**
     * 创建 MCP Tool 节点执行器。
     */
    public McpToolDagNodeExecutor(McpResearchGateway researchGateway,
                                  McpInvocationService invocationService,
                                  ObjectMapper objectMapper) {
        this.researchGateway = researchGateway;
        this.invocationService = invocationService;
        this.objectMapper = objectMapper;
    }

    /** {@inheritDoc} */
    @Override
    public Set<NodeType> supportedTypes() {
        return Set.of(NodeType.MCP_TOOL);
    }

    /**
     * {@inheritDoc}
     * 配置 capabilityId 时直接调用已注册能力，否则执行兼容研究逻辑。
     */
    @Override
    public DagNodeExecutionResult execute(DagNodeExecutionContext context) {
        JsonNode config = context.node().getConfig();
        String capabilityId = config == null ? null : config.path("capabilityId").asText(null);
        if (StringUtils.hasText(capabilityId)) {
            McpInvocationResult result = invocationService.invoke(new McpInvocationRequest(
                    context.run().getRunId(), "dag:" + context.node().getId(), capabilityId, context.input()));
            if (!result.successful()) {
                throw new IllegalStateException("MCP 能力调用失败: " + result.errorCode()
                        + " - " + result.errorMessage());
            }
            return DagNodeExecutionResult.success(result.content());
        }
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

    /**
     * 按“运行输入优先、节点配置兜底”的顺序读取文本参数。
     */
    private String text(JsonNode input, String field, JsonNode config) {
        JsonNode inputValue = input == null ? null : input.get(field);
        if (inputValue != null && !inputValue.isNull()) {
            return inputValue.asText();
        }
        return config == null ? null : config.path(field).asText(null);
    }
}
