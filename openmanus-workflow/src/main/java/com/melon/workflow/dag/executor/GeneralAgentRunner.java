package com.melon.workflow.dag.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.melon.agent.runtime.AgentRun;

/** Application entry point for the general chat Agent shared by chat and DAG. */
@FunctionalInterface
public interface GeneralAgentRunner {

    String AGENT_ID = "general-chat-agent";

    /** 使用 DAG 的目标和映射输入调用聊天共用的通用 Agent。 */
    AgentRun run(String objective, JsonNode input);
}
