package com.melon.workflow.dag.model;

import java.time.Instant;

public record DagWorkflowStartResponse(
        String runId,
        String workflowCode,
        int workflowVersion,
        DagWorkflowRun.RunStatus status,
        Instant createdAt
) {
    public static DagWorkflowStartResponse from(DagWorkflowRun run) {
        return new DagWorkflowStartResponse(
                run.getRunId(),
                run.getWorkflowCode(),
                run.getWorkflowVersion(),
                run.getStatus(),
                run.getCreatedAt());
    }
}
