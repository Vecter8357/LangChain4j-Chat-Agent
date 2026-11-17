package com.melon.workflow.dag.engine;

import com.melon.workflow.dag.model.DagWorkflowDefinition;

import java.util.List;
import java.util.Map;

public record CompiledDagWorkflow(
        DagWorkflowDefinition definition,
        List<String> topologicalOrder,
        Map<String, DagWorkflowDefinition.Node> nodesById,
        Map<String, List<DagWorkflowDefinition.Edge>> incomingEdges,
        Map<String, List<DagWorkflowDefinition.Edge>> outgoingEdges
) {
}
