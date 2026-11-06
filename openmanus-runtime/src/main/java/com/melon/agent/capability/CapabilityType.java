package com.melon.agent.capability;

/**
 * 平台能力类型，用于选择对应的调用器并区分能力的运行边界。
 */
public enum CapabilityType {
    MCP_TOOL,
    LOCAL_TOOL,
    KNOWLEDGE,
    MEMORY,
    AGENT,
    WORKFLOW
}
