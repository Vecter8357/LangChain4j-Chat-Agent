package com.melon.workflow.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.workflow.dag.engine.CompiledDagWorkflow;
import com.melon.workflow.dag.engine.DagWorkflowCompiler;
import com.melon.workflow.dag.model.DagWorkflowDefinition;
import com.melon.workflow.dag.model.DagWorkflowDefinition.Edge;
import com.melon.workflow.dag.model.DagWorkflowDefinition.InputBinding;
import com.melon.workflow.dag.model.DagWorkflowDefinition.Node;
import com.melon.workflow.dag.model.DagWorkflowDefinition.NodeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DagWorkflowCompilerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DagWorkflowCompiler compiler = new DagWorkflowCompiler();

    /** 验证合法定义可编译且上游节点先于依赖节点。 */
    @Test
    void compilesAValidDagAndOrdersDependencies() {
        DagWorkflowDefinition definition = validDefinition();

        CompiledDagWorkflow compiled = compiler.compile(definition);

        assertThat(compiled.topologicalOrder()).containsExactly("start", "analyze", "end");
        assertThat(compiled.incomingEdges().get("analyze")).hasSize(1);
    }

    /** 验证编译器拒绝环形依赖。 */
    @Test
    void rejectsCycles() {
        DagWorkflowDefinition definition = validDefinition();
        definition.getEdges().add(edge("analyze", "start"));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("环形依赖");
    }

    /** 验证节点不能通过输入映射引用非上游节点。 */
    @Test
    void rejectsReferencesToNonUpstreamNodes() {
        DagWorkflowDefinition definition = validDefinition();
        InputBinding invalid = new InputBinding();
        invalid.setPath("$.nodes.end.output.text");
        definition.getNodes().get(1).setInputMappings(Map.of("future", invalid));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只能引用上游节点输出");
    }

    /** 验证配置系统提示词的旧内联 AGENT 节点可以编译。 */
    @Test
    void acceptsAgentNodeWithSystemPrompt() {
        DagWorkflowDefinition definition = validDefinition();
        Node agent = definition.getNodes().get(1);
        agent.setType(NodeType.AGENT);
        agent.setConfig(objectMapper.createObjectNode().put("systemPrompt", "You are a research agent."));

        CompiledDagWorkflow compiled = compiler.compile(definition);

        assertThat(compiled.nodesById().get("analyze").getType()).isEqualTo(NodeType.AGENT);
    }

    /** 验证兼容研究节点必须提供问题来源。 */
    @Test
    void rejectsResearchNodeWithoutQuestionBinding() {
        DagWorkflowDefinition definition = validDefinition();
        Node research = definition.getNodes().get(1);
        research.setType(NodeType.MCP_TOOL);
        research.setInputMappings(Map.of());
        research.setConfig(objectMapper.createObjectNode().put("operation", "research"));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputMappings.question");
    }

    /** 构造用于修改和校验的合法 DAG。 */
    private DagWorkflowDefinition validDefinition() {
        DagWorkflowDefinition definition = new DagWorkflowDefinition();
        definition.setCode("compiler-test");
        Node start = node("start", NodeType.START);
        Node analyze = node("analyze", NodeType.CONDITION);
        InputBinding source = new InputBinding();
        source.setPath("$.nodes.start.output.sourceText");
        analyze.setInputMappings(Map.of("sourceText", source));
        Node end = node("end", NodeType.END);
        definition.setNodes(List.of(start, analyze, end));
        definition.setEdges(new java.util.ArrayList<>(List.of(
                edge("start", "analyze"), edge("analyze", "end"))));
        return definition;
    }

    /** 构造包含节点 ID、类型和基础配置的测试节点。 */
    private Node node(String id, NodeType type) {
        Node node = new Node();
        node.setId(id);
        node.setType(type);
        node.setConfig(objectMapper.createObjectNode());
        return node;
    }

    /** 构造两个测试节点之间的有向边。 */
    private Edge edge(String from, String to) {
        Edge edge = new Edge();
        edge.setFrom(from);
        edge.setTo(to);
        return edge;
    }
}
