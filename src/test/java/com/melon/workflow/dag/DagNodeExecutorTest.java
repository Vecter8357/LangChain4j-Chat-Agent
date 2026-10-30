package com.melon.workflow.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.melon.workflow.McpResearchGateway;
import com.melon.mcp.McpInvocationService;
import com.melon.mcp.McpInvocationResult;
import com.melon.workflow.WorkflowModelGateway;
import com.melon.workflow.dag.executor.DagNodeExecutionContext;
import com.melon.workflow.dag.executor.LlmDagNodeExecutor;
import com.melon.workflow.dag.executor.McpToolDagNodeExecutor;
import com.melon.workflow.dag.model.DagWorkflowDefinition.Node;
import com.melon.workflow.dag.model.DagWorkflowDefinition.NodeType;
import com.melon.workflow.dag.model.DagWorkflowRun;
import com.melon.workflow.model.ModelRole;
import com.melon.workflow.model.RewriteWorkflowState.ResearchEvidence;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DagNodeExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void llmNodeUsesConfiguredRoleAndReturnsTextOutput() {
        WorkflowModelGateway gateway = mock(WorkflowModelGateway.class);
        when(gateway.text(any(), anyString(), anyString())).thenReturn("模型节点结果");
        LlmDagNodeExecutor executor = new LlmDagNodeExecutor(gateway, objectMapper);
        Node node = node("writer", NodeType.LLM);
        ObjectNode config = objectMapper.createObjectNode();
        config.put("role", "DRAFT_WRITER");
        config.put("instruction", "完成短文");
        node.setConfig(config);

        var result = executor.execute(new DagNodeExecutionContext(
                new DagWorkflowRun(), node, objectMapper.createObjectNode().put("source", "原文"), 1));

        assertThat(result.output().path("text").asText()).isEqualTo("模型节点结果");
        verify(gateway).text(ModelRole.DRAFT_WRITER, "完成短文", "{\"source\":\"原文\"}");
    }

    @Test
    void mcpNodeMapsResolvedInputsToResearchGateway() {
        McpResearchGateway gateway = mock(McpResearchGateway.class);
        ResearchEvidence evidence = new ResearchEvidence();
        evidence.setSuccessful(true);
        evidence.setContent("检索结果");
        when(gateway.research(any())).thenReturn(evidence);
        McpToolDagNodeExecutor executor = new McpToolDagNodeExecutor(
                gateway, mock(McpInvocationService.class), objectMapper);
        Node node = node("search", NodeType.MCP_TOOL);
        node.setConfig(objectMapper.createObjectNode().put("operation", "research"));
        ObjectNode input = objectMapper.createObjectNode();
        input.put("question", "当前网络热度");
        input.put("purpose", "方向评分");

        var result = executor.execute(new DagNodeExecutionContext(new DagWorkflowRun(), node, input, 1));

        assertThat(result.output().path("successful").asBoolean()).isTrue();
        assertThat(result.output().path("content").asText()).isEqualTo("检索结果");
        verify(gateway).research(any());
    }

    @Test
    void mcpResearchAcceptsQueryAliasAsQuestion() {
        McpResearchGateway gateway = mock(McpResearchGateway.class);
        ResearchEvidence evidence = new ResearchEvidence();
        evidence.setSuccessful(true);
        when(gateway.research(any())).thenReturn(evidence);
        McpToolDagNodeExecutor executor = new McpToolDagNodeExecutor(
                gateway, mock(McpInvocationService.class), objectMapper);
        Node node = node("search", NodeType.MCP_TOOL);
        node.setConfig(objectMapper.createObjectNode().put("operation", "research"));

        executor.execute(new DagNodeExecutionContext(new DagWorkflowRun(), node,
                objectMapper.createObjectNode().put("query", "Agent 平台趋势"), 1));

        ArgumentCaptor<com.melon.workflow.model.RewriteWorkflowState.ResearchPlanItem> captor =
                ArgumentCaptor.forClass(com.melon.workflow.model.RewriteWorkflowState.ResearchPlanItem.class);
        verify(gateway).research(captor.capture());
        assertThat(captor.getValue().getQuestion()).isEqualTo("Agent 平台趋势");
    }

    @Test
    void mcpNodeInvokesRegisteredCapabilityWhenCapabilityIdIsConfigured() {
        McpResearchGateway gateway = mock(McpResearchGateway.class);
        McpInvocationService invocationService = mock(McpInvocationService.class);
        when(invocationService.invoke(any())).thenReturn(McpInvocationResult.success(
                "mcp.search.query", "search", "query",
                objectMapper.createObjectNode().put("answer", "统一调用结果"), 10));
        McpToolDagNodeExecutor executor = new McpToolDagNodeExecutor(
                gateway, invocationService, objectMapper);
        Node node = node("search", NodeType.MCP_TOOL);
        node.setConfig(objectMapper.createObjectNode().put("capabilityId", "mcp.search.query"));

        var result = executor.execute(new DagNodeExecutionContext(
                new DagWorkflowRun(), node, objectMapper.createObjectNode().put("query", "Agent"), 1));

        assertThat(result.output().path("answer").asText()).isEqualTo("统一调用结果");
        verify(invocationService).invoke(any());
    }

    private Node node(String id, NodeType type) {
        Node node = new Node();
        node.setId(id);
        node.setType(type);
        return node;
    }
}
