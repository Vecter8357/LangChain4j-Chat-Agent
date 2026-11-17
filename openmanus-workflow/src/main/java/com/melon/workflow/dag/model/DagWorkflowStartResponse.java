package com.melon.workflow.dag.model;

import java.time.Instant;

public record DagWorkflowStartResponse(
        String runId,
        String workflowCode,
        int workflowVersion,
        DagWorkflowRun.RunStatus status,
        Instant createdAt
) {
    /** 从运行快照提取启动响应所需的标识、版本和状态。 */
    public static DagWorkflowStartResponse from(DagWorkflowRun run) {
        return new DagWorkflowStartResponse(
                run.getRunId(),
                run.getWorkflowCode(),
                run.getWorkflowVersion(),
                run.getStatus(),
                run.getCreatedAt());
    }
}
