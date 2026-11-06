package com.melon.agent.runtime;

import com.melon.agent.capability.CapabilityType;

import java.util.Set;

/**
 * 某类 Capability 的执行适配器，由 Agent Runtime 通过类型进行路由。
 */
public interface CapabilityInvoker {

    /**
     * 返回当前调用器支持的能力类型。
     *
     * @return 能力类型集合
     */
    Set<CapabilityType> supportedTypes();

    /**
     * 执行一次能力调用。
     *
     * @param request 标准能力调用请求
     * @return 标准能力调用结果
     */
    CapabilityInvocation.Result invoke(CapabilityInvocation.Request request);
}
