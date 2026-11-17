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

    /** 自然完成、预算收束或异常终止的原因，与执行状态分别记录。 */
    private FinishReason finishReason;

    /** 尝试提交的模型调用次数，包含最后一次收束。 */
    private int modelCallCount;

    /** 尝试提交的工具调用次数，包含失败、超时及调用池拒绝。 */
    private int toolCallCount;

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

    /** 描述产生最终结果或停止执行的具体原因。 */
    public enum FinishReason {
        FINAL_ANSWER,
        MAX_MODEL_ROUNDS,
        MAX_TOOL_CALLS,
        MAX_TOOL_CALLS_PER_ROUND,
        REPEATED_TOOL_CALL,
        RUN_TIMEOUT,
        MODEL_TIMEOUT,
        TOOL_TIMEOUT,
        CANCELLED,
        APPROVAL_REQUIRED,
        INVOCATION_REJECTED,
        FINALIZATION_FAILED,
        EXECUTION_ERROR
    }
}
