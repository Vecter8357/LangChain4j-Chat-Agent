package com.melon.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "agent-platform.runtime")
public class AgentRuntimeProperties {
    private boolean enabled = true;

    private int defaultMaxSteps = 8;

    private int maximumMaxSteps = 30;

    private int maximumCapabilities = 64;

    /** 一次运行允许实际提交的工具调用总数。 */
    private int maxToolCalls = 16;

    /** 一条模型消息允许执行的工具请求数，超出部分返回配对的拒绝结果。 */
    private int maxToolCallsPerRound = 4;

    /** 相同参数连续取得相同结果的允许次数，下一次相同调用触发收束。 */
    private int maxRepeatedToolCalls = 2;

    /** Runtime 的总时间预算，包含预留的最终收束时间。 */
    private Duration maxRunDuration = Duration.ofMinutes(10);

    /** 单次普通模型调用的等待上限。 */
    private Duration maxModelDuration = Duration.ofMinutes(2);

    /** 单次工具调用的等待上限。 */
    private Duration maxToolDuration = Duration.ofMinutes(2);

    /** 为额外一次无工具收束保留的调用时间。 */
    private Duration finalizationDuration = Duration.ofSeconds(30);

    /** 同一 Runtime 实例最多同时执行的模型和工具调用，另有一个有界排队位置。 */
    private int maxConcurrentInvocations = 16;
}
