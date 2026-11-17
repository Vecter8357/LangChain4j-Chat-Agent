package com.melon.workflow.dag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.melon.config.properties.DagWorkflowProperties;
import com.melon.workflow.WorkflowExecutionException;
import com.melon.workflow.dag.engine.CompiledDagWorkflow;
import com.melon.workflow.dag.engine.DagConditionEvaluator;
import com.melon.agent.runtime.JsonSchemaValidator;
import com.melon.workflow.dag.engine.DagVariableResolver;
import com.melon.workflow.dag.engine.DagWorkflowCompiler;
import com.melon.workflow.dag.executor.DagNodeExecutionContext;
import com.melon.workflow.dag.executor.DagNodeExecutor;
import com.melon.workflow.dag.executor.DagNodeExecutorRegistry;
import com.melon.workflow.dag.model.DagWorkflowDefinition;
import com.melon.workflow.dag.model.DagWorkflowDefinition.Edge;
import com.melon.workflow.dag.model.DagWorkflowDefinition.JoinMode;
import com.melon.workflow.dag.model.DagWorkflowDefinition.Node;
import com.melon.workflow.dag.model.DagWorkflowDefinition.NodeType;
import com.melon.workflow.dag.model.DagWorkflowRun;
import com.melon.workflow.dag.model.DagWorkflowRun.NodeRun;
import com.melon.workflow.dag.model.DagWorkflowRun.NodeStatus;
import com.melon.workflow.dag.model.DagWorkflowRun.RunEvent;
import com.melon.workflow.dag.model.DagWorkflowRun.RunStatus;
import com.melon.workflow.dag.model.DagWorkflowRunRequest;
import com.melon.workflow.dag.persistence.DagWorkflowRunRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Service
public class DagWorkflowService {

    private final DagWorkflowCompiler compiler;
    private final DagVariableResolver variableResolver;
    private final DagConditionEvaluator conditionEvaluator;
    private final JsonSchemaValidator schemaValidator;
    private final DagNodeExecutorRegistry executorRegistry;
    private final DagWorkflowRunRepository repository;
    private final DagWorkflowProperties properties;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final Executor nodeInvocationExecutor;
    private final Set<String> activeRuns = ConcurrentHashMap.newKeySet();

    /** 装配 DAG 编译、变量解析、节点调度和运行记录存储。 */
    public DagWorkflowService(DagWorkflowCompiler compiler,
                              DagVariableResolver variableResolver,
                              DagConditionEvaluator conditionEvaluator,
                              JsonSchemaValidator schemaValidator,
                              DagNodeExecutorRegistry executorRegistry,
                              DagWorkflowRunRepository repository,
                              DagWorkflowProperties properties,
                              ObjectMapper objectMapper,
                              @Qualifier("dagWorkflowExecutor") Executor executor,
                              @Qualifier("dagNodeInvocationExecutor") Executor nodeInvocationExecutor) {
        this.compiler = compiler;
        this.variableResolver = variableResolver;
        this.conditionEvaluator = conditionEvaluator;
        this.schemaValidator = schemaValidator;
        this.executorRegistry = executorRegistry;
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.nodeInvocationExecutor = nodeInvocationExecutor;
    }

    /** 校验工作流定义并返回可执行的拓扑结构。 */
    public CompiledDagWorkflow validate(DagWorkflowDefinition definition) {
        return compiler.compile(definition);
    }

    /** 校验定义和输入，保存独立的运行快照后提交执行队列。 */
    public DagWorkflowRun start(DagWorkflowRunRequest request) {
        if (!properties.isEnabled()) {
            throw new WorkflowExecutionException("DAG 工作流已禁用");
        }
        if (request == null || request.getDefinition() == null) {
            throw new IllegalArgumentException("definition 不能为空");
        }
        DagWorkflowDefinition snapshot=objectMapper.convertValue(request.getDefinition(),DagWorkflowDefinition.class);
        CompiledDagWorkflow compiled = compiler.compile(snapshot);
        JsonNode input = request.getInput() == null
                ? objectMapper.createObjectNode()
                : request.getInput().deepCopy();
        schemaValidator.validate(input, request.getDefinition().getInputSchema(), "工作流输入");

        DagWorkflowRun run = new DagWorkflowRun();
        run.setRunId(UUID.randomUUID().toString());
        run.setWorkflowCode(snapshot.getCode());
        run.setWorkflowVersion(snapshot.getVersion());
        run.setDefinitionSnapshot(snapshot);
        run.setInput(input);
        run.setCreatedAt(Instant.now());
        for (Node node : snapshot.getNodes()) {
            NodeRun nodeRun = new NodeRun();
            nodeRun.setNodeId(node.getId());
            nodeRun.setNodeType(node.getType());
            run.getNodeRuns().put(node.getId(), nodeRun);
        }
        addEvent(run, null, "RUN_QUEUED", "DAG 工作流已进入执行队列");
        repository.save(run);

        activeRuns.add(run.getRunId());
        try {
            executor.execute(() -> execute(run, compiled));
        } catch (RuntimeException error) {
            activeRuns.remove(run.getRunId());
            failRun(run, null, "QUEUE_REJECTED", rootMessage(error));
            throw new WorkflowExecutionException("DAG 工作流执行队列已满", error);
        }
        return run;
    }

    /** 读取指定运行快照，空白 ID 直接返回空结果。 */
    public Optional<DagWorkflowRun> findById(String runId) {
        if (!StringUtils.hasText(runId)) {
            return Optional.empty();
        }
        return repository.findById(runId.trim());
    }

    /**
     * 返回最近运行记录，供设计器调试面板查看历史结果。
     */
    public List<DagWorkflowRun> findRecent(String workflowCode, int limit) {
        return repository.findRecent(workflowCode, limit);
    }

    /** 按拓扑顺序执行命中条件的节点并保存运行结果与事件。 */
    private void execute(DagWorkflowRun run, CompiledDagWorkflow compiled) {
        run.setStatus(RunStatus.RUNNING);
        addEvent(run, null, "RUN_STARTED", "开始执行 DAG 工作流");
        repository.save(run);
        try {
            for (String nodeId : compiled.topologicalOrder()) {
                Node node = compiled.nodesById().get(nodeId);
                NodeRun nodeRun = run.getNodeRuns().get(nodeId);
                if (!shouldExecute(run, node, compiled.incomingEdges().get(nodeId))) {
                    nodeRun.setStatus(NodeStatus.SKIPPED);
                    nodeRun.setCompletedAt(Instant.now());
                    addEvent(run, nodeId, "NODE_SKIPPED", "节点未命中有效入边");
                    repository.save(run);
                    continue;
                }
                if (!executeNode(run, node, nodeRun)) {
                    return;
                }
            }

            Node end = compiled.nodesById().values().stream()
                    .filter(node -> node.getType() == NodeType.END)
                    .findFirst()
                    .orElseThrow();
            NodeRun endRun = run.getNodeRuns().get(end.getId());
            if (endRun.getStatus() != NodeStatus.SUCCEEDED) {
                failRun(run, endRun, "END_NOT_REACHED", "工作流没有到达 END 节点");
                return;
            }
            run.setOutput(resolveWorkflowOutput(run, endRun));
            run.setStatus(RunStatus.COMPLETED);
            run.setCompletedAt(Instant.now());
            addEvent(run, end.getId(), "RUN_COMPLETED", "DAG 工作流执行完成");
            repository.save(run);
        } catch (Throwable error) {
            failRun(run, null, "RUNTIME_ERROR", rootMessage(error));
        } finally {
            activeRuns.remove(run.getRunId());
        }
    }

    /** 根据上游成功状态、边条件及汇合模式判断节点是否应执行。 */
    private boolean shouldExecute(DagWorkflowRun run, Node node, List<Edge> incoming) {
        if (node.getType() == NodeType.START) {
            return true;
        }
        if (incoming == null || incoming.isEmpty()) {
            return false;
        }
        JsonNode context = variableResolver.context(run);
        long active = incoming.stream().filter(edge -> {
            NodeRun source = run.getNodeRuns().get(edge.getFrom());
            return source.getStatus() == NodeStatus.SUCCEEDED
                    && conditionEvaluator.matches(context, edge.getCondition());
        }).count();
        return node.getJoinMode() == JoinMode.ALL ? active == incoming.size() : active > 0;
    }

    /** 解析并校验节点输入，在超时和重试限制下执行节点并校验输出。 */
    private boolean executeNode(DagWorkflowRun run, Node node, NodeRun nodeRun) {
        JsonNode input = node.getType() == NodeType.START
                ? run.getInput().deepCopy()
                : variableResolver.resolveInputs(run, node);
        JsonNode config = node.getConfig();
        schemaValidator.validate(input, config == null ? null : config.get("inputSchema"),
                "节点 " + node.getId() + " 输入");
        nodeRun.setInput(input);

        int maxAttempts = node.getRetryPolicy() == null
                ? 1
                : Math.max(1, node.getRetryPolicy().getMaxAttempts());
        Throwable lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            nodeRun.setAttempt(attempt);
            nodeRun.setStatus(NodeStatus.RUNNING);
            nodeRun.setStartedAt(Instant.now());
            nodeRun.setCompletedAt(null);
            nodeRun.setDurationMillis(0);
            nodeRun.setErrorCode(null);
            nodeRun.setErrorMessage(null);
            addEvent(run, node.getId(), "NODE_STARTED",
                    "开始执行节点，attempt=" + attempt);
            repository.save(run);
            try {
                DagNodeExecutor nodeExecutor = executorRegistry.get(node.getType());
                DagNodeExecutionContext context = new DagNodeExecutionContext(run, node, input, attempt);
                CompletableFuture<DagNodeExecutor.DagNodeExecutionResult> invocation =
                        CompletableFuture.supplyAsync(() -> nodeExecutor.execute(context), nodeInvocationExecutor);
                DagNodeExecutor.DagNodeExecutionResult result;
                try {
                    result = invocation.get(node.getTimeoutSeconds(), TimeUnit.SECONDS);
                } catch (Exception invocationError) {
                    invocation.cancel(true);
                    throw invocationError;
                }
                JsonNode output = result.output() == null
                        ? JsonNodeFactory.instance.nullNode()
                        : result.output();
                schemaValidator.validate(output, config == null ? null : config.get("outputSchema"),
                        "节点 " + node.getId() + " 输出");
                nodeRun.setOutput(output);
                nodeRun.setStatus(NodeStatus.SUCCEEDED);
                nodeRun.setCompletedAt(Instant.now());
                nodeRun.setDurationMillis(duration(nodeRun));
                addEvent(run, node.getId(), "NODE_SUCCEEDED", "节点执行成功");
                repository.save(run);
                return true;
            } catch (Throwable error) {
                lastError = error;
                nodeRun.setErrorCode("NODE_EXECUTION_FAILED");
                nodeRun.setErrorMessage(rootMessage(error));
                nodeRun.setCompletedAt(Instant.now());
                nodeRun.setDurationMillis(duration(nodeRun));
                if (attempt < maxAttempts) {
                    addEvent(run, node.getId(), "NODE_RETRY",
                            "节点执行失败，准备重试: " + rootMessage(error));
                    repository.save(run);
                }
            }
        }
        nodeRun.setStatus(NodeStatus.FAILED);
        failRun(run, nodeRun, "NODE_FAILED", rootMessage(lastError));
        return false;
    }

    /** 优先按显式输出映射组装结果，省略映射时使用 END 节点输出。 */
    private JsonNode resolveWorkflowOutput(DagWorkflowRun run, NodeRun endRun) {
        Map<String, DagWorkflowDefinition.InputBinding> mappings = run.getDefinitionSnapshot().getOutputMappings();
        if (mappings == null || mappings.isEmpty()) {
            return endRun.getOutput();
        }
        Node outputNode = new Node();
        outputNode.setId("$workflow-output");
        outputNode.setInputMappings(mappings);
        ObjectNode output = variableResolver.resolveInputs(run, outputNode);
        return output;
    }

    /** 更新运行与相关节点的失败状态，写入失败事件并保存快照。 */
    private void failRun(DagWorkflowRun run, NodeRun nodeRun, String errorCode, String message) {
        if (nodeRun != null) {
            nodeRun.setStatus(NodeStatus.FAILED);
            nodeRun.setErrorCode(errorCode);
            nodeRun.setErrorMessage(message);
            nodeRun.setCompletedAt(Instant.now());
            nodeRun.setDurationMillis(duration(nodeRun));
        }
        run.setStatus(RunStatus.FAILED);
        run.setFailureReason(message);
        run.setCompletedAt(Instant.now());
        addEvent(run, nodeRun == null ? null : nodeRun.getNodeId(), "RUN_FAILED", message);
        repository.save(run);
    }

    /** 根据一次节点执行的起止时间计算毫秒数，缺少时间时返回零。 */
    private long duration(NodeRun nodeRun) {
        if (nodeRun.getStartedAt() == null || nodeRun.getCompletedAt() == null) {
            return 0;
        }
        return Duration.between(nodeRun.getStartedAt(), nodeRun.getCompletedAt()).toMillis();
    }

    /** 为运行追加带序号、节点 ID 和发生时间的事件。 */
    private void addEvent(DagWorkflowRun run, String nodeId, String type, String summary) {
        RunEvent event = new RunEvent();
        event.setSequence(run.getEvents().size() + 1);
        event.setNodeId(nodeId);
        event.setType(type);
        event.setSummary(summary);
        event.setOccurredAt(Instant.now());
        run.getEvents().add(event);
    }

    /** 保留工作流异常中的角色及路由信息，其他异常使用底层消息。 */
    private String rootMessage(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        Throwable current = error;
        while (current.getCause() != null) {
            if (current instanceof WorkflowExecutionException && StringUtils.hasText(current.getMessage())) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
