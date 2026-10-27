package com.melon.agent.runtime;

import com.melon.agent.capability.CapabilityType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * CapabilityType 到具体 CapabilityInvoker 的唯一映射表。
 */
@Component
public class CapabilityInvokerRegistry {

    private final Map<CapabilityType, CapabilityInvoker> invokers = new EnumMap<>(CapabilityType.class);

    /**
     * 收集所有调用器并校验同一能力类型不存在重复实现。
     *
     * @param invocationServices Spring 容器中的调用器
     */
    public CapabilityInvokerRegistry(List<CapabilityInvoker> invocationServices) {
        for (CapabilityInvoker invoker : invocationServices) {
            for (CapabilityType type : invoker.supportedTypes()) {
                CapabilityInvoker previous = invokers.putIfAbsent(type, invoker);
                if (previous != null) {
                    throw new IllegalStateException("能力类型存在多个调用器: " + type);
                }
            }
        }
    }

    /**
     * 获取指定类型的调用器。
     *
     * @param type 能力类型
     * @return 对应调用器
     * @throws IllegalArgumentException 未注册对应调用器时抛出
     */
    public CapabilityInvoker require(CapabilityType type) {
        CapabilityInvoker invoker = invokers.get(type);
        if (invoker == null) {
            throw new IllegalArgumentException("能力类型尚未配置调用器: " + type);
        }
        return invoker;
    }
}
