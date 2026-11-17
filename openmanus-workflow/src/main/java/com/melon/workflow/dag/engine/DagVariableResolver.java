package com.melon.workflow.dag.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.melon.workflow.dag.model.DagWorkflowDefinition.InputBinding;
import com.melon.workflow.dag.model.DagWorkflowDefinition.Node;
import com.melon.workflow.dag.model.DagWorkflowRun;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DagVariableResolver {

    private static final Pattern SEGMENT = Pattern.compile("([A-Za-z0-9_-]+)|\\[(\\d+)]");
    private final ObjectMapper objectMapper;

    /** 保存用于创建运行上下文和映射结果的 JSON 映射器。 */
    public DagVariableResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 将运行输入、节点状态与输出，以及运行标识组装成路径查询上下文。 */
    public ObjectNode context(DagWorkflowRun run) {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("input", safe(run.getInput()));
        ObjectNode nodes = root.putObject("nodes");
        run.getNodeRuns().forEach((nodeId, nodeRun) -> {
            ObjectNode node = nodes.putObject(nodeId);
            node.put("status", nodeRun.getStatus().name());
            node.set("output", safe(nodeRun.getOutput()));
        });
        ObjectNode system = root.putObject("system");
        system.put("runId", run.getRunId());
        system.put("workflowCode", run.getWorkflowCode());
        system.put("workflowVersion", run.getWorkflowVersion());
        return root;
    }

    /** 根据常量或路径生成节点输入，必填路径缺失时报告错误。 */
    public ObjectNode resolveInputs(DagWorkflowRun run, Node node) {
        ObjectNode resolved = objectMapper.createObjectNode();
        Map<String, InputBinding> mappings = node.getInputMappings();
        if (mappings == null) {
            return resolved;
        }
        JsonNode context = context(run);
        mappings.forEach((name, binding) -> {
            JsonNode value;
            if (StringUtils.hasText(binding.getPath())) {
                value = resolve(context, binding.getPath());
                if (value.isMissingNode() && binding.isRequired()) {
                    throw new IllegalArgumentException("节点 " + node.getId() + " 的必填输入不存在: " + binding.getPath());
                }
            } else {
                value = binding.getValue() == null ? JsonNodeFactory.instance.nullNode() : binding.getValue();
            }
            resolved.set(name, value.isMissingNode() ? JsonNodeFactory.instance.nullNode() : value.deepCopy());
        });
        return resolved;
    }

    /** 解析受支持的属性及数组下标路径，无法解析时返回缺失节点。 */
    public JsonNode resolve(JsonNode root, String path) {
        if ("$".equals(path)) {
            return root;
        }
        if (!StringUtils.hasText(path) || !path.startsWith("$.")) {
            return JsonNodeFactory.instance.missingNode();
        }
        JsonNode current = root;
        Matcher matcher = SEGMENT.matcher(path.substring(2));
        while (matcher.find()) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return JsonNodeFactory.instance.missingNode();
            }
            if (matcher.group(1) != null) {
                current = current.path(matcher.group(1));
            } else {
                int index = Integer.parseInt(matcher.group(2));
                current = current.isArray() && index < current.size()
                        ? current.path(index)
                        : JsonNodeFactory.instance.missingNode();
            }
        }
        return current == null ? JsonNodeFactory.instance.missingNode() : current;
    }

    /** 将 Java 空引用转换为 JSON null 节点。 */
    private JsonNode safe(JsonNode value) {
        return value == null ? JsonNodeFactory.instance.nullNode() : value;
    }
}
