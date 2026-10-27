package com.melon.workflow.dag.model;

import java.util.List;

public record DagWorkflowValidationResponse(
        boolean valid,
        String workflowCode,
        int workflowVersion,
        List<String> topologicalOrder
) {
}
