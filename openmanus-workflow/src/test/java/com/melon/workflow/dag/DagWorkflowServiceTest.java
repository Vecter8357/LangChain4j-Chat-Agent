package com.melon.workflow.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.melon.config.properties.DagWorkflowProperties;
import com.melon.workflow.dag.engine.DagConditionEvaluator;
import com.melon.agent.runtime.JsonSchemaValidator;
import com.melon.workflow.dag.engine.DagVariableResolver;
import com.melon.workflow.dag.engine.DagWorkflowCompiler;
import com.melon.workflow.dag.executor.DagNodeExecutorRegistry;
import com.melon.workflow.dag.executor.PassthroughDagNodeExecutor;
import com.melon.workflow.dag.model.DagWorkflowDefinition;
import com.melon.workflow.dag.model.DagWorkflowDefinition.ConditionOperator;
import com.melon.workflow.dag.model.DagWorkflowDefinition.Edge;
import com.melon.workflow.dag.model.DagWorkflowDefinition.EdgeCondition;
import com.melon.workflow.dag.model.DagWorkflowDefinition.InputBinding;
import com.melon.workflow.dag.model.DagWorkflowDefinition.Node;
import com.melon.workflow.dag.model.DagWorkflowDefinition.NodeType;
import com.melon.workflow.dag.model.DagWorkflowRun;
import com.melon.workflow.dag.model.DagWorkflowRun.RunStatus;
import com.melon.workflow.dag.model.DagWorkflowRunRequest;
import com.melon.workflow.dag.persistence.DagWorkflowRunRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DagWorkflowServiceTest {

    /** 验证上游输出映射、条件边执行和运行定义快照隔离。 */
    @Test
    void resolvesPreviousOutputsAndExecutesConditionalEdge() {
        ObjectMapper objectMapper = new ObjectMapper();
        DagVariableResolver resolver = new DagVariableResolver(objectMapper);
        DagWorkflowRunRepository repository = mock(DagWorkflowRunRepository.class);
        DagNodeExecutorRegistry registry = new DagNodeExecutorRegistry(List.of(new PassthroughDagNodeExecutor()));
        Executor directExecutor = Runnable::run;
        DagWorkflowService service = new DagWorkflowService(
                new DagWorkflowCompiler(), resolver, new DagConditionEvaluator(resolver),
                new JsonSchemaValidator(), registry, repository, new DagWorkflowProperties(),
                objectMapper, directExecutor, directExecutor);

        DagWorkflowRunRequest request = new DagWorkflowRunRequest();
        request.setDefinition(definition(objectMapper));
        ObjectNode input = objectMapper.createObjectNode();
        input.put("approved", true);
        input.put("message", "条件分支执行成功");
        request.setInput(input);

        DagWorkflowRun run = service.start(request);
        request.getDefinition().setCode("later-edit");
        request.getDefinition().getNodes().getFirst().setName("later-name");
        assertThat(run.getDefinitionSnapshot().getCode()).isEqualTo("conditional-pass-through");
        assertThat(run.getDefinitionSnapshot().getNodes().getFirst().getName()).isNotEqualTo("later-name");

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.getNodeRuns().get("condition").getInput().path("approved").asBoolean()).isTrue();
        assertThat(run.getOutput().path("message").asText()).isEqualTo("条件分支执行成功");
        verify(repository, atLeast(4)).save(run);
    }

    /** 创建带条件边和上游映射的工作流测试定义。 */
    private DagWorkflowDefinition definition(ObjectMapper objectMapper) {
        DagWorkflowDefinition definition = new DagWorkflowDefinition();
        definition.setCode("conditional-pass-through");

        Node start = node("start", NodeType.START, objectMapper);
        Node condition = node("condition", NodeType.CONDITION, objectMapper);
        condition.setInputMappings(Map.of("approved", path("$.nodes.start.output.approved")));
        Node end = node("end", NodeType.END, objectMapper);
        end.setInputMappings(Map.of("message", path("$.nodes.start.output.message")));

        Edge conditional = edge("condition", "end");
        EdgeCondition edgeCondition = new EdgeCondition();
        edgeCondition.setPath("$.nodes.condition.output.approved");
        edgeCondition.setOperator(ConditionOperator.EQ);
        edgeCondition.setValue(objectMapper.getNodeFactory().booleanNode(true));
        conditional.setCondition(edgeCondition);

        definition.setNodes(List.of(start, condition, end));
        definition.setEdges(List.of(edge("start", "condition"), conditional));
        return definition;
    }

    /** 创建带空 JSON 配置的测试节点。 */
    private Node node(String id, NodeType type, ObjectMapper objectMapper) {
        Node node = new Node();
        node.setId(id);
        node.setType(type);
        node.setConfig(objectMapper.createObjectNode());
        return node;
    }

    /** 创建用于连接测试节点的有向边。 */
    private Edge edge(String from, String to) {
        Edge edge = new Edge();
        edge.setFrom(from);
        edge.setTo(to);
        return edge;
    }

    /** 将路径包装成测试用的输入绑定。 */
    private InputBinding path(String path) {
        InputBinding binding = new InputBinding();
        binding.setPath(path);
        return binding;
    }
}
