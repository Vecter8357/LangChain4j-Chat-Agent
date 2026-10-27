package com.melon.agent.runtime;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次 Agent 执行的聚合状态，包含最终结果和完整步骤记录。
 */
@Data
public class AgentRun {

    private String runId;

    private String agentId;

    private Status status = Status.RUNNING;

    private String objective;

    private String finalAnswer;

    private String failureReason;

    private List<AgentStep> steps = new ArrayList<>();

    private Instant createdAt;

    private Instant completedAt;

    /**
     * Agent 执行生命周期状态。
     */
    public enum Status {
        RUNNING,
        COMPLETED,
        AWAITING_APPROVAL,
        FAILED,
        CANCELLED
    }
}
