package com.melon.workflow.model;

import java.time.Instant;

public record RewriteWorkflowStartResponse(
        String workflowId,
        RewriteWorkflowState.WorkflowStatus status,
        String currentNode,
        Instant createdAt
) {
    /** 从改编状态提取启动或续跑响应需要的字段。 */
    public static RewriteWorkflowStartResponse from(RewriteWorkflowState state) {
        return new RewriteWorkflowStartResponse(
                state.getWorkflowId(),
                state.getStatus(),
                state.getCurrentNode(),
                state.getCreatedAt()
        );
    }
}
