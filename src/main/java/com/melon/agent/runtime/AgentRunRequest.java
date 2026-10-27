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
}
