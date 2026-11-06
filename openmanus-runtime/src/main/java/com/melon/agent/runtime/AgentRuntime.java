package com.melon.agent.runtime;

/**
 * Agent 执行内核契约，负责模型决策与能力调用之间的多轮循环。
 */
public interface AgentRuntime {

    /**
     * 同步执行一次 Agent Run，直到完成、失败、达到限制或等待审批。
     *
     * @param request Agent 定义、目标及结构化输入
     * @return 完整运行结果
     */
    AgentRun execute(AgentRunRequest request);
}
