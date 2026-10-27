package com.melon.agent.runtime;

import lombok.Data;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Agent 的运行定义，描述身份、系统指令、授权能力和执行步数限制。
 */
@Data
public class AgentDefinition {

    private String id;

    private String name;

    private String systemPrompt;

    private Set<String> capabilityIds = new LinkedHashSet<>();

    private int maxSteps;
}
