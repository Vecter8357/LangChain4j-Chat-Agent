package com.melon.agent.runtime;

import com.melon.config.properties.AgentRuntimeProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 单次运行独占的预算和重复结果记录，使用单调时钟计算剩余时间。 */
final class AgentExecutionBudget {

    private final long startedAt = System.nanoTime();
    private final long runNanos;
    private final long modelNanos;
    private final long toolNanos;
    private final long finalizationNanos;
    private final int maxToolCalls;
    private final int maxToolCallsPerRound;
    private final int maxRepeatedToolCalls;
    private final Map<String, ObservationStreak> observations = new HashMap<>();
    private final Set<String> toolCallIds = new HashSet<>();

    /** 在运行开始时冻结预算配置，后续配置变更作用于下一次运行。 */
    AgentExecutionBudget(AgentRuntimeProperties properties) {
        runNanos = positiveNanos(properties.getMaxRunDuration(), "max-run-duration");
        modelNanos = positiveNanos(properties.getMaxModelDuration(), "max-model-duration");
        toolNanos = positiveNanos(properties.getMaxToolDuration(), "max-tool-duration");
        finalizationNanos = positiveNanos(properties.getFinalizationDuration(), "finalization-duration");
        maxToolCalls = positive(properties.getMaxToolCalls(), "max-tool-calls");
        maxToolCallsPerRound = positive(properties.getMaxToolCallsPerRound(), "max-tool-calls-per-round");
        maxRepeatedToolCalls = positive(properties.getMaxRepeatedToolCalls(), "max-repeated-tool-calls");
        positive(properties.getDefaultMaxSteps(), "default-max-steps");
        positive(properties.getMaximumMaxSteps(), "maximum-max-steps");
        if (finalizationNanos >= runNanos) {
            throw new IllegalArgumentException("finalization-duration 必须小于 max-run-duration");
        }
    }

    /** 获取硬截止前剩余的时间，可用于最后的收束调用。 */
    long remainingNanos() {
        return Math.max(0, runNanos - (System.nanoTime() - startedAt));
    }

    /** 普通行动仅使用扣除收束预留后的时间。 */
    long actionNanos() {
        return Math.max(0, remainingNanos() - finalizationNanos);
    }

    /** 计算一次模型请求能够使用的等待时间。 */
    long modelTimeout(boolean finalization) {
        if (finalization) {
            return Math.min(remainingNanos(), Math.min(modelNanos, finalizationNanos));
        }
        return Math.min(actionNanos(), modelNanos);
    }

    /** 工具请求同时受单工具时限和普通行动截止时间限制。 */
    long toolTimeout() {
        return Math.min(actionNanos(), toolNanos);
    }

    /** 在每次实际提交前检查总工具次数。 */
    boolean toolsExhausted(int submittedCalls) {
        return submittedCalls >= maxToolCalls;
    }

    /** 对当前模型消息中的请求按下标限制单轮工具数量。 */
    boolean roundExhausted(int requestIndex) {
        return requestIndex >= maxToolCallsPerRound;
    }

    /** 相同参数已连续取得足够次数的相同结果时，阻止再次执行。 */
    boolean repeated(String signature) {
        ObservationStreak previous = observations.get(signature);
        return previous != null && previous.count() >= maxRepeatedToolCalls;
    }

    /** 只累计未带来新结果的调用，结果变化后重新开始计数。 */
    void observe(String signature, String result) {
        ObservationStreak previous = observations.get(signature);
        int count = previous != null && previous.result().equals(result) ? previous.count() + 1 : 1;
        observations.put(signature, new ObservationStreak(result, count));
    }

    /** 保留合法且首次出现的模型 ID，为缺失或重复的 ID 分配新的值。 */
    String toolCallId(String requestedId) {
        if (requestedId != null && !requestedId.isBlank() && toolCallIds.add(requestedId)) {
            return requestedId;
        }
        String generated;
        do {
            generated = UUID.randomUUID().toString();
        } while (!toolCallIds.add(generated));
        return generated;
    }

    /** 校验次数限制，避免零值配置使预算控制失效。 */
    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " 必须大于 0");
        return value;
    }

    /** 将有效的正时长转换为纳秒，溢出值按配置错误处理。 */
    private static long positiveNanos(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " 必须为正时长");
        }
        return value.toNanos();
    }

    private record ObservationStreak(String result, int count) { }
}
