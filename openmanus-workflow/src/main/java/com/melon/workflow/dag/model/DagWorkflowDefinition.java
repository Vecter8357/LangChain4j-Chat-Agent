package com.melon.workflow.dag.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class DagWorkflowDefinition {

    private String code;
    private String name;
    private int version = 1;
    private JsonNode inputSchema;
    private List<Node> nodes = new ArrayList<>();
    private List<Edge> edges = new ArrayList<>();
    private Map<String, InputBinding> outputMappings = new LinkedHashMap<>();

    @Data
    public static class Node {
        private String id;
        private String name;
        private NodeType type;
        private JoinMode joinMode = JoinMode.ANY;
        private Map<String, InputBinding> inputMappings = new LinkedHashMap<>();
        private JsonNode config;
        private int timeoutSeconds = 120;
        private RetryPolicy retryPolicy = new RetryPolicy();
    }

    @Data
    public static class Edge {
        private String id;
        private String from;
        private String to;
        private EdgeCondition condition;
    }

    @Data
    public static class InputBinding {
        private String path;
        private JsonNode value;
        private boolean required = true;
    }

    @Data
    public static class EdgeCondition {
        private String path;
        private ConditionOperator operator = ConditionOperator.TRUTHY;
        private JsonNode value;
    }

    @Data
    public static class RetryPolicy {
        private int maxAttempts = 1;
        private long backoffMillis;
    }

    public enum NodeType {
        START,
        LLM,
        AGENT,
        MCP_TOOL,
        CONDITION,
        END
    }

    public enum JoinMode {
        ANY,
        ALL
    }

    public enum ConditionOperator {
        EQ,
        NE,
        GT,
        GTE,
        LT,
        LTE,
        CONTAINS,
        EXISTS,
        NOT_EXISTS,
        TRUTHY,
        FALSY
    }
}
