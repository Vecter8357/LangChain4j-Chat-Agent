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

    @Test
    void compilesAValidDagAndOrdersDependencies() {
        DagWorkflowDefinition definition = validDefinition();

        CompiledDagWorkflow compiled = compiler.compile(definition);

        assertThat(compiled.topologicalOrder()).containsExactly("start", "analyze", "end");
        assertThat(compiled.incomingEdges().get("analyze")).hasSize(1);
    }

    @Test
    void rejectsCycles() {
        DagWorkflowDefinition definition = validDefinition();
        definition.getEdges().add(edge("analyze", "start"));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("环形依赖");
    }

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

    @Test
    void acceptsAgentNodeWithSystemPrompt() {
        DagWorkflowDefinition definition = validDefinition();
        Node agent = definition.getNodes().get(1);
        agent.setType(NodeType.AGENT);
        agent.setConfig(objectMapper.createObjectNode().put("systemPrompt", "You are a research agent."));

        CompiledDagWorkflow compiled = compiler.compile(definition);

        assertThat(compiled.nodesById().get("analyze").getType()).isEqualTo(NodeType.AGENT);
    }

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

    private Node node(String id, NodeType type) {
        Node node = new Node();
        node.setId(id);
        node.setType(type);
        node.setConfig(objectMapper.createObjectNode());
        return node;
    }

    private Edge edge(String from, String to) {
        Edge edge = new Edge();
        edge.setFrom(from);
        edge.setTo(to);
        return edge;
    }
}
