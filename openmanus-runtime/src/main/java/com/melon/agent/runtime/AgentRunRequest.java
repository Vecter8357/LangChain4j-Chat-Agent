package com.melon.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 启动一次 Agent 执行所需的请求数据。
 */
@Data
public class AgentRunRequest {

    private AgentDefinition definition;

    private String objective;

    private JsonNode input;

    /** 服务端装配的历史消息，仅当前执行使用，不作为持久化数据契约。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private java.util.List<dev.langchain4j.data.message.ChatMessage> history = java.util.List.of();

    /** 召回的历史摘录和记忆资料，不得覆盖本轮目标或系统规则。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String memoryContext;
}
