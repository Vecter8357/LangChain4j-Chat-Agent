package com.melon.workflow.dag.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.melon.workflow.dag.model.DagWorkflowDefinition.NodeType;

import java.util.Map;
import java.util.Set;

public interface DagNodeExecutor {

    Set<NodeType> supportedTypes();

    DagNodeExecutionResult execute(DagNodeExecutionContext context);

    record DagNodeExecutionResult(JsonNode output, Map<String, Object> metrics) {
        public static DagNodeExecutionResult success(JsonNode output) {
            return new DagNodeExecutionResult(output, Map.of());
        }
    }
}
