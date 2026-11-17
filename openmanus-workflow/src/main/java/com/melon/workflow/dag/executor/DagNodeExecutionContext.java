package com.melon.workflow.dag.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.melon.workflow.dag.model.DagWorkflowDefinition;
import com.melon.workflow.dag.model.DagWorkflowRun;

public record DagNodeExecutionContext(
        DagWorkflowRun run,
        DagWorkflowDefinition.Node node,
        JsonNode input,
        int attempt
) {
}
