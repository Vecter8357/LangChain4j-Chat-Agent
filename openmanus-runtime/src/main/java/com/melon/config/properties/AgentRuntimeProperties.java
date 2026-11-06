package com.melon.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "agent-platform.runtime")
public class AgentRuntimeProperties {
    private boolean enabled = true;

    private int defaultMaxSteps = 8;

    private int maximumMaxSteps = 30;

    private int maximumCapabilities = 64;
}
