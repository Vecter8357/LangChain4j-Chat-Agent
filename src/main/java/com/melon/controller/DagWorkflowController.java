package com.melon.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.melon.agent.capability.CapabilityRegistry;
import com.melon.workflow.WorkflowExecutionException;
import com.melon.workflow.dag.DagWorkflowDefinitionService;
import com.melon.workflow.dag.DagWorkflowService;
import com.melon.workflow.dag.engine.CompiledDagWorkflow;
import com.melon.workflow.dag.model.DagWorkflowCatalogResponse;
import com.melon.workflow.dag.model.DagWorkflowCatalogResponse.NodeTemplate;
import com.melon.workflow.dag.model.DagWorkflowDefinition;
import com.melon.workflow.dag.model.DagWorkflowDefinition.ConditionOperator;
import com.melon.workflow.dag.model.DagWorkflowDefinition.JoinMode;
import com.melon.workflow.dag.model.DagWorkflowDefinition.NodeType;
import com.melon.workflow.dag.model.DagWorkflowDesign;
import com.melon.workflow.dag.model.DagWorkflowRun;
import com.melon.workflow.dag.model.DagWorkflowRunRequest;
import com.melon.workflow.dag.model.DagWorkflowStartResponse;
import com.melon.workflow.dag.model.DagWorkflowValidationResponse;
import com.melon.workflow.dag.model.DagWorkflowValidationResponse.ValidationIssue;
import com.melon.workflow.model.ModelRole;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DAG 工作流的设计、校验、执行和调试 API。
 */
@RestController
@RequestMapping("/api/dag-workflows")
public class DagWorkflowController {

    private static final Pattern NODE_PATTERN = Pattern.compile("节点\\s+([A-Za-z0-9_-]+)");
    private static final Pattern EDGE_PATTERN = Pattern.compile("边\\s+([A-Za-z0-9_-]+)\\s*->\\s*([A-Za-z0-9_-]+)");

    private final DagWorkflowService workflowService;
    private final DagWorkflowDefinitionService definitionService;
    private final CapabilityRegistry capabilityRegistry;
    private final ObjectMapper objectMapper;

    /** 创建工作流控制器。 */
    public DagWorkflowController(DagWorkflowService workflowService,
                                 DagWorkflowDefinitionService definitionService,
                                 CapabilityRegistry capabilityRegistry,
                                 ObjectMapper objectMapper) {
        this.workflowService = workflowService;
        this.definitionService = definitionService;
        this.capabilityRegistry = capabilityRegistry;
        this.objectMapper = objectMapper;
    }

    /** 返回设计器节点模板、角色、条件运算符和能力目录。 */
    @GetMapping("/catalog")
    public DagWorkflowCatalogResponse catalog() {
        return new DagWorkflowCatalogResponse(
                nodeTemplates(),
                Arrays.stream(ModelRole.values()).map(Enum::name).toList(),
                Arrays.stream(ConditionOperator.values()).map(Enum::name).toList(),
                Arrays.stream(JoinMode.values()).map(Enum::name).toList(),
                capabilityRegistry.list());
    }

    /** 校验定义并始终返回可供画布定位的结构化结果。 */
    @PostMapping("/validate")
    public DagWorkflowValidationResponse validate(@RequestBody DagWorkflowDefinition definition) {
        try {
            CompiledDagWorkflow compiled = workflowService.validate(definition);
            return new DagWorkflowValidationResponse(true, definition.getCode(), definition.getVersion(),
                    compiled.topologicalOrder(), List.of(), List.of());
        } catch (IllegalArgumentException error) {
            return new DagWorkflowValidationResponse(false,
                    definition == null ? null : definition.getCode(),
                    definition == null ? 0 : definition.getVersion(),
                    List.of(), issues(error.getMessage()), List.of());
        }
    }

    /** 保存或覆盖一个经过校验的工作流草稿。 */
    @PutMapping("/definitions/{code}")
    public DagWorkflowDesign saveDefinition(@PathVariable String code,
                                            @RequestBody DagWorkflowDesign design) {
        return definitionService.save(code, design);
    }

    /** 返回全部工作流设计摘要及画布数据。 */
    @GetMapping("/definitions")
    public List<DagWorkflowDesign> definitions() {
        return definitionService.findAll();
    }

    /** 按编码读取工作流设计。 */
    @GetMapping("/definitions/{code}")
    public ResponseEntity<DagWorkflowDesign> definition(@PathVariable String code) {
        return definitionService.findByCode(code)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 删除工作流设计。 */
    @DeleteMapping("/definitions/{code}")
    public ResponseEntity<Void> deleteDefinition(@PathVariable String code) {
        return definitionService.delete(code)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** 异步启动工作流运行。 */
    @PostMapping("/runs")
    public ResponseEntity<DagWorkflowStartResponse> start(@RequestBody DagWorkflowRunRequest request) {
        DagWorkflowRun run = workflowService.start(request);
        URI statusUri = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{runId}")
                .buildAndExpand(run.getRunId())
                .toUri();
        return ResponseEntity.accepted().location(statusUri).body(DagWorkflowStartResponse.from(run));
    }

    /** 查询最近工作流运行，可按工作流编码过滤。 */
    @GetMapping("/runs")
    public List<DagWorkflowRun> recentRuns(
            @RequestParam(required = false) String workflowCode,
            @RequestParam(defaultValue = "20") int limit) {
        return workflowService.findRecent(workflowCode, limit);
    }

    /** 按运行 ID 查询完整调试状态。 */
    @GetMapping("/runs/{runId}")
    public ResponseEntity<DagWorkflowRun> get(@PathVariable String runId) {
        return workflowService.findById(runId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 将业务异常转换为稳定的 JSON 错误响应。 */
    @ExceptionHandler({IllegalArgumentException.class, WorkflowExecutionException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(RuntimeException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now());
        body.put("error", error.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** 构造各节点类型的默认配置，供前端创建节点。 */
    private List<NodeTemplate> nodeTemplates() {
        ObjectNode agentConfig = objectMapper.createObjectNode()
                .put("systemPrompt", "你是一个专用任务 Agent。")
                .put("objective", "根据输入完成任务")
                .put("maxSteps", 6);
        agentConfig.set("capabilityIds", objectMapper.createArrayNode());
        return List.of(
                template(NodeType.START, "开始", "工作流输入入口", objectMapper.createObjectNode()),
                template(NodeType.LLM, "模型", "单次模型分析或生成",
                        objectMapper.createObjectNode()
                                .put("role", ModelRole.ANALYZER.name())
                                .put("instruction", "请根据输入完成任务。")
                                .put("responseMode", "TEXT")),
                template(NodeType.AGENT, "Agent", "模型自主选择多个能力完成目标", agentConfig),
                template(NodeType.MCP_TOOL, "MCP Tool", "确定性调用一个 MCP 能力",
                        objectMapper.createObjectNode().put("operation", "research")),
                template(NodeType.CONDITION, "条件", "承接输入，分支条件配置在连线上",
                        objectMapper.createObjectNode()),
                template(NodeType.END, "结束", "汇总工作流最终输出", objectMapper.createObjectNode()));
    }

    /** 创建节点模板。 */
    private NodeTemplate template(NodeType type, String label, String description, ObjectNode config) {
        return new NodeTemplate(type.name(), label, description, config);
    }

    /** 把编译器的聚合消息转换为设计器可消费的定位信息。 */
    private List<ValidationIssue> issues(String message) {
        String normalized = message == null ? "工作流定义无效" : message
                .replaceFirst("^DAG 工作流定义无效:\\s*", "");
        return Arrays.stream(normalized.split("；"))
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(this::issue)
                .toList();
    }

    /** 推断单条校验消息对应的画布目标及字段。 */
    private ValidationIssue issue(String message) {
        Matcher edge = EDGE_PATTERN.matcher(message);
        if (edge.find()) {
            return new ValidationIssue("EDGE", edge.group(1) + "->" + edge.group(2),
                    field(message), code(message), message);
        }
        Matcher node = NODE_PATTERN.matcher(message);
        if (node.find()) {
            return new ValidationIssue("NODE", node.group(1), field(message), code(message), message);
        }
        return new ValidationIssue("WORKFLOW", null, field(message), code(message), message);
    }

    /** 根据消息内容生成便于前端聚合的字段名。 */
    private String field(String message) {
        String value = message.toLowerCase(Locale.ROOT);
        if (value.contains("role")) return "config.role";
        if (value.contains("instruction")) return "config.instruction";
        if (value.contains("systemprompt")) return "config.systemPrompt";
        if (value.contains("capabilityid")) return "config.capabilityId";
        if (value.contains("question") || value.contains("研究问题")) return "inputMappings.question";
        if (value.contains("timeoutseconds")) return "timeoutSeconds";
        if (value.contains("retrypolicy")) return "retryPolicy";
        if (value.contains("输入映射") || value.contains("引用")) return "inputMappings";
        if (value.contains("条件")) return "condition";
        if (value.contains("输出")) return "outputMappings";
        if (value.contains("code")) return "code";
        return null;
    }

    /** 根据消息内容生成稳定的通用错误码。 */
    private String code(String message) {
        if (message.contains("不能为空") || message.contains("必须配置")) return "REQUIRED";
        if (message.contains("重复")) return "DUPLICATE";
        if (message.contains("不存在")) return "NOT_FOUND";
        if (message.contains("环形")) return "CYCLE";
        if (message.contains("不可达")) return "UNREACHABLE";
        if (message.contains("只能引用")) return "INVALID_REFERENCE";
        return "INVALID_DEFINITION";
    }
}
