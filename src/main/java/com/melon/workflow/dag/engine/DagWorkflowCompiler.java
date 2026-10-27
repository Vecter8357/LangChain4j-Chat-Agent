package com.melon.workflow.dag.engine;

import com.melon.workflow.dag.model.DagWorkflowDefinition;
import com.melon.workflow.dag.model.DagWorkflowDefinition.Edge;
import com.melon.workflow.dag.model.DagWorkflowDefinition.InputBinding;
import com.melon.workflow.dag.model.DagWorkflowDefinition.Node;
import com.melon.workflow.dag.model.DagWorkflowDefinition.NodeType;
import com.melon.workflow.model.ModelRole;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DagWorkflowCompiler {

    private static final Pattern NODE_REFERENCE = Pattern.compile("^\\$\\.nodes\\.([A-Za-z0-9_-]+)(?:\\.|\\[|$)");
    private static final Pattern SUPPORTED_PATH = Pattern.compile("^\\$(?:\\.[A-Za-z0-9_-]+|\\[\\d+])*$");

    public CompiledDagWorkflow compile(DagWorkflowDefinition definition) {
        List<String> errors = new ArrayList<>();
        if (definition == null) {
            throw new IllegalArgumentException("工作流定义不能为空");
        }
        if (!StringUtils.hasText(definition.getCode())) {
            errors.add("workflow.code 不能为空");
        }
        if (definition.getVersion() < 1) {
            errors.add("workflow.version 必须大于 0");
        }
        if (definition.getNodes() == null || definition.getNodes().isEmpty()) {
            errors.add("工作流至少需要一个节点");
            throwIfInvalid(errors);
        }

        Map<String, Node> nodesById = new LinkedHashMap<>();
        for (Node node : definition.getNodes()) {
            if (node == null || !StringUtils.hasText(node.getId())) {
                errors.add("节点 id 不能为空");
                continue;
            }
            if (nodesById.putIfAbsent(node.getId(), node) != null) {
                errors.add("节点 id 重复: " + node.getId());
            }
            if (node.getType() == null) {
                errors.add("节点 " + node.getId() + " 未配置 type");
            }
            if (node.getTimeoutSeconds() < 1) {
                errors.add("节点 " + node.getId() + " timeoutSeconds 必须大于 0");
            }
            if (node.getRetryPolicy() != null && node.getRetryPolicy().getMaxAttempts() < 1) {
                errors.add("节点 " + node.getId() + " retryPolicy.maxAttempts 必须大于 0");
            }
            validateNodeConfig(node, errors);
        }

        long startCount = nodesById.values().stream().filter(node -> node.getType() == NodeType.START).count();
        long endCount = nodesById.values().stream().filter(node -> node.getType() == NodeType.END).count();
        if (startCount != 1) {
            errors.add("第一版 DAG 必须且只能包含一个 START 节点");
        }
        if (endCount != 1) {
            errors.add("第一版 DAG 必须且只能包含一个 END 节点");
        }

        Map<String, List<Edge>> incoming = emptyEdgeMap(nodesById.keySet());
        Map<String, List<Edge>> outgoing = emptyEdgeMap(nodesById.keySet());
        List<Edge> edges = definition.getEdges() == null ? List.of() : definition.getEdges();
        for (Edge edge : edges) {
            if (edge == null || !StringUtils.hasText(edge.getFrom()) || !StringUtils.hasText(edge.getTo())) {
                errors.add("边的 from 和 to 不能为空");
                continue;
            }
            if (!nodesById.containsKey(edge.getFrom())) {
                errors.add("边引用了不存在的起点: " + edge.getFrom());
            }
            if (!nodesById.containsKey(edge.getTo())) {
                errors.add("边引用了不存在的终点: " + edge.getTo());
            }
            if (edge.getFrom().equals(edge.getTo())) {
                errors.add("节点不能连接自身: " + edge.getFrom());
            }
            if (nodesById.containsKey(edge.getFrom()) && nodesById.containsKey(edge.getTo())) {
                outgoing.get(edge.getFrom()).add(edge);
                incoming.get(edge.getTo()).add(edge);
            }
            validateConditionPath(edge, errors);
        }

        nodesById.values().forEach(node -> {
            if (node.getType() == NodeType.START && !incoming.get(node.getId()).isEmpty()) {
                errors.add("START 节点不能有入边: " + node.getId());
            }
            if (node.getType() == NodeType.END && !outgoing.get(node.getId()).isEmpty()) {
                errors.add("END 节点不能有出边: " + node.getId());
            }
        });

        List<String> topologicalOrder = topologicalSort(nodesById, incoming, outgoing, errors);
        validateReachability(nodesById, outgoing, errors);
        validateBindings(nodesById, incoming, errors);
        validateEdgeConditionReferences(edges, nodesById, incoming, errors);
        validateWorkflowOutputs(definition, nodesById, errors);
        throwIfInvalid(errors);
        return new CompiledDagWorkflow(definition, List.copyOf(topologicalOrder),
                Map.copyOf(nodesById), immutableEdgeMap(incoming), immutableEdgeMap(outgoing));
    }

    private void validateNodeConfig(Node node, List<String> errors) {
        if (node == null || node.getType() == null) {
            return;
        }
        if (node.getType() == NodeType.LLM) {
            if (node.getConfig() == null
                    || !StringUtils.hasText(node.getConfig().path("role").asText())
                    || !StringUtils.hasText(node.getConfig().path("instruction").asText())) {
                errors.add("LLM 节点 " + node.getId() + " 必须配置 config.role 和 config.instruction");
            } else {
                try {
                    ModelRole.valueOf(node.getConfig().path("role").asText().trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException error) {
                    errors.add("LLM 节点 " + node.getId() + " 配置了未知 role: "
                            + node.getConfig().path("role").asText());
                }
            }
        }
        if (node.getType() == NodeType.MCP_TOOL && node.getConfig() != null) {
            String capabilityId = node.getConfig().path("capabilityId").asText();
            String operation = node.getConfig().path("operation").asText("research");
            if (!StringUtils.hasText(capabilityId) && !"research".equalsIgnoreCase(operation)) {
                errors.add("MCP_TOOL 必须配置 config.capabilityId，或使用兼容 research 操作: " + operation);
            }
        }
        if (node.getType() == NodeType.AGENT
                && (node.getConfig() == null
                || !StringUtils.hasText(node.getConfig().path("systemPrompt").asText()))) {
            errors.add("AGENT 节点 " + node.getId() + " 必须配置 config.systemPrompt");
        }
    }

    private void validateBindings(Map<String, Node> nodesById,
                                  Map<String, List<Edge>> incoming,
                                  List<String> errors) {
        for (Node node : nodesById.values()) {
            Map<String, InputBinding> bindings = node.getInputMappings();
            if (bindings == null) {
                continue;
            }
            Set<String> ancestors = ancestorsOf(node.getId(), incoming);
            bindings.forEach((name, binding) -> {
                if (!StringUtils.hasText(name) || binding == null) {
                    errors.add("节点 " + node.getId() + " 包含无效输入映射");
                    return;
                }
                if (!StringUtils.hasText(binding.getPath())) {
                    if (binding.getValue() == null && binding.isRequired()) {
                        errors.add("节点 " + node.getId() + " 的输入 " + name + " 未配置 path 或 value");
                    }
                    return;
                }
                validatePath(binding.getPath(), "节点 " + node.getId() + " 的输入 " + name, errors);
                referencedNode(binding.getPath()).ifPresent(referenced -> {
                    if (!nodesById.containsKey(referenced)) {
                        errors.add("节点 " + node.getId() + " 引用了不存在的节点输出: " + referenced);
                    } else if (!ancestors.contains(referenced)) {
                        errors.add("节点 " + node.getId() + " 只能引用上游节点输出，当前引用: " + referenced);
                    }
                });
            });
        }
    }

    private void validateConditionPath(Edge edge, List<String> errors) {
        if (edge != null && edge.getCondition() != null) {
            if (!StringUtils.hasText(edge.getCondition().getPath())) {
                errors.add("边 " + edge.getFrom() + " -> " + edge.getTo() + " 的条件 path 不能为空");
            } else {
                validatePath(edge.getCondition().getPath(),
                        "边 " + edge.getFrom() + " -> " + edge.getTo() + " 的条件", errors);
            }
            if (edge.getCondition().getOperator() == null) {
                errors.add("边 " + edge.getFrom() + " -> " + edge.getTo() + " 的条件 operator 不能为空");
            }
        }
    }

    private void validateEdgeConditionReferences(List<Edge> edges,
                                                 Map<String, Node> nodesById,
                                                 Map<String, List<Edge>> incoming,
                                                 List<String> errors) {
        for (Edge edge : edges) {
            if (edge == null || edge.getCondition() == null || !StringUtils.hasText(edge.getCondition().getPath())) {
                continue;
            }
            referencedNode(edge.getCondition().getPath()).ifPresent(referenced -> {
                if (!nodesById.containsKey(referenced)) {
                    errors.add("条件边引用了不存在的节点输出: " + referenced);
                    return;
                }
                Set<String> available = ancestorsOf(edge.getFrom(), incoming);
                available.add(edge.getFrom());
                if (!available.contains(referenced)) {
                    errors.add("条件边 " + edge.getFrom() + " -> " + edge.getTo()
                            + " 引用了尚未执行的节点: " + referenced);
                }
            });
        }
    }

    private void validateWorkflowOutputs(DagWorkflowDefinition definition,
                                         Map<String, Node> nodesById,
                                         List<String> errors) {
        if (definition.getOutputMappings() == null) {
            return;
        }
        definition.getOutputMappings().forEach((name, binding) -> {
            if (!StringUtils.hasText(name) || binding == null || !StringUtils.hasText(binding.getPath())) {
                errors.add("工作流输出映射必须配置名称和 path");
                return;
            }
            validatePath(binding.getPath(), "工作流输出 " + name, errors);
            referencedNode(binding.getPath()).ifPresent(referenced -> {
                if (!nodesById.containsKey(referenced)) {
                    errors.add("工作流输出 " + name + " 引用了不存在的节点: " + referenced);
                }
            });
        });
    }

    private void validatePath(String path, String subject, List<String> errors) {
        if (!SUPPORTED_PATH.matcher(path).matches()) {
            errors.add(subject + " 使用了不支持的路径语法: " + path);
        }
    }

    private java.util.Optional<String> referencedNode(String path) {
        Matcher matcher = NODE_REFERENCE.matcher(path);
        return matcher.find() ? java.util.Optional.of(matcher.group(1)) : java.util.Optional.empty();
    }

    private Set<String> ancestorsOf(String nodeId, Map<String, List<Edge>> incoming) {
        Set<String> ancestors = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(nodeId);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (Edge edge : incoming.getOrDefault(current, List.of())) {
                if (ancestors.add(edge.getFrom())) {
                    queue.add(edge.getFrom());
                }
            }
        }
        return ancestors;
    }

    private List<String> topologicalSort(Map<String, Node> nodes,
                                         Map<String, List<Edge>> incoming,
                                         Map<String, List<Edge>> outgoing,
                                         List<String> errors) {
        Map<String, Integer> indegree = new HashMap<>();
        nodes.keySet().forEach(id -> indegree.put(id, incoming.get(id).size()));
        Queue<String> ready = new ArrayDeque<>();
        nodes.keySet().stream().filter(id -> indegree.get(id) == 0).forEach(ready::add);
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String current = ready.remove();
            order.add(current);
            for (Edge edge : outgoing.get(current)) {
                int remaining = indegree.compute(edge.getTo(), (ignored, value) -> value - 1);
                if (remaining == 0) {
                    ready.add(edge.getTo());
                }
            }
        }
        if (order.size() != nodes.size()) {
            errors.add("第一版 DAG 不允许环形依赖");
        }
        return order;
    }

    private void validateReachability(Map<String, Node> nodes,
                                      Map<String, List<Edge>> outgoing,
                                      List<String> errors) {
        Node start = nodes.values().stream().filter(node -> node.getType() == NodeType.START).findFirst().orElse(null);
        if (start == null) {
            return;
        }
        Set<String> visited = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(start.getId());
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            outgoing.getOrDefault(current, List.of()).forEach(edge -> queue.add(edge.getTo()));
        }
        nodes.keySet().stream().filter(id -> !visited.contains(id))
                .forEach(id -> errors.add("节点从 START 不可达: " + id));
    }

    private Map<String, List<Edge>> emptyEdgeMap(Set<String> nodeIds) {
        Map<String, List<Edge>> result = new LinkedHashMap<>();
        nodeIds.forEach(id -> result.put(id, new ArrayList<>()));
        return result;
    }

    private Map<String, List<Edge>> immutableEdgeMap(Map<String, List<Edge>> source) {
        Map<String, List<Edge>> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }

    private void throwIfInvalid(List<String> errors) {
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("DAG 工作流定义无效: " + String.join("；", errors));
        }
    }
}
