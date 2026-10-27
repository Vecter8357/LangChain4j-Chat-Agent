package com.melon.agent.capability;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Capability Registry 是平台的能力目录。
 *
 * 它解决的是：
 * 平台当前有哪些模型、工具、MCP、知识库或子 Agent，哪些 Agent 可以使用它们
 */
/**
 * 基于并发 Map 的 Capability Registry 实现，适用于当前单实例运行期目录。
 */
@Component
public class InMemoryCapabilityRegistry implements CapabilityRegistry {

    private final Map<String, CapabilityDescriptor> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idByModelToolName = new ConcurrentHashMap<>();

    /**
     * {@inheritDoc}
     * 同时维护能力 ID 和模型工具名两个索引，并拒绝工具名冲突。
     */
    @Override
    public void register(CapabilityDescriptor capability) {
        CapabilityDescriptor previous = byId.put(capability.id(), capability);
        if (previous != null && StringUtils.hasText(previous.modelToolName())) {
            idByModelToolName.remove(previous.modelToolName(), previous.id());
        }
        if (StringUtils.hasText(capability.modelToolName())) {
            String collision = idByModelToolName.putIfAbsent(capability.modelToolName(), capability.id());
            if (collision != null && !collision.equals(capability.id())) {
                byId.remove(capability.id(), capability);
                throw new IllegalArgumentException("模型工具名称重复: " + capability.modelToolName());
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void removeByProvider(String providerId) {
        if (!StringUtils.hasText(providerId)) {
            return;
        }
        new ArrayList<>(byId.values()).stream()
                .filter(capability -> providerId.equals(capability.providerId()))
                .forEach(capability -> {
                    byId.remove(capability.id(), capability);
                    if (StringUtils.hasText(capability.modelToolName())) {
                        idByModelToolName.remove(capability.modelToolName(), capability.id());
                    }
                });
    }

    /** {@inheritDoc} */
    @Override
    public Optional<CapabilityDescriptor> findById(String capabilityId) {
        return Optional.ofNullable(byId.get(capabilityId));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<CapabilityDescriptor> findByModelToolName(String modelToolName) {
        return Optional.ofNullable(idByModelToolName.get(modelToolName)).map(byId::get);
    }

    /** {@inheritDoc} */
    @Override
    public List<CapabilityDescriptor> findEnabled(Collection<String> capabilityIds) {
        if (capabilityIds == null || capabilityIds.isEmpty()) {
            return List.of();
        }
        return capabilityIds.stream()
                .distinct()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .filter(CapabilityDescriptor::enabled)
                .sorted(Comparator.comparing(CapabilityDescriptor::id))
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public List<CapabilityDescriptor> list() {
        return byId.values().stream()
                .sorted(Comparator.comparing(CapabilityDescriptor::id))
                .toList();
    }
}
