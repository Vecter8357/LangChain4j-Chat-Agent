package com.melon.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;

/**
 * Agent Run 中的一条可审计步骤，可表示模型决策或能力调用。
 */
@Data
public class AgentStep {

    private int sequence;

    private StepType type;

    private String modelText;

    private String capabilityId;

    private String modelToolName;

    private JsonNode arguments;

    private JsonNode observation;

    private boolean successful = true;

    private String errorCode;

    private String errorMessage;

    private long durationMillis;

    private Instant occurredAt = Instant.now();

    /**
     * 步骤来源类型。
     */
    public enum StepType {
        MODEL,
        CAPABILITY
    }
}
