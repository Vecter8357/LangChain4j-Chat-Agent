package com.melon.agent.capability;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 平台能力目录，负责能力的注册、发现和授权能力筛选，不负责真正执行能力。
 */
public interface CapabilityRegistry {

    /**
     * 注册或更新一个能力。
     *
     * @param capability 能力描述
     */
    void register(CapabilityDescriptor capability);

    /**
     * 移除某个提供方注册的全部能力，通常在 MCP 工具列表刷新前调用。
     *
     * @param providerId 提供方标识
     */
    void removeByProvider(String providerId);

    /**
     * 根据全局能力 ID 查询能力。
     *
     * @param capabilityId 能力 ID
     * @return 查询结果
     */
    Optional<CapabilityDescriptor> findById(String capabilityId);

    /**
     * 根据模型发出的工具名称反查能力。
     *
     * @param modelToolName 模型工具名
     * @return 查询结果
     */
    Optional<CapabilityDescriptor> findByModelToolName(String modelToolName);

    /**
     * 按给定 ID 顺序筛选已启用能力。
     *
     * @param capabilityIds 待筛选能力 ID
     * @return 已启用且存在的能力
     */
    List<CapabilityDescriptor> findEnabled(Collection<String> capabilityIds);

    /**
     * 返回当前注册的全部能力快照。
     *
     * @return 按能力 ID 排序的能力列表
     */
    List<CapabilityDescriptor> list();
}
