package com.melon.agent.capability;

import com.melon.mcp.McpInvocationService;
import com.melon.mcp.McpServerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 按需发现 MCP Tools 并确保指定提供方的能力已进入 Capability Registry。
 */
@Slf4j
@Service
public class CapabilityProvisioningService {

    private final CapabilityRegistry capabilityRegistry;
    private final McpInvocationService mcpInvocationService;
    private final McpServerRegistry mcpServerRegistry;

    /**
     * 创建按需能力供应服务。
     */
    public CapabilityProvisioningService(CapabilityRegistry capabilityRegistry,
                                         McpInvocationService mcpInvocationService,
                                         McpServerRegistry mcpServerRegistry) {
        this.capabilityRegistry = capabilityRegistry;
        this.mcpInvocationService = mcpInvocationService;
        this.mcpServerRegistry = mcpServerRegistry;
    }

    /**
     * 确保指定 MCP 提供方已经完成工具发现；单个提供方失败不会阻断其他提供方。
     *
     * @param providerIds MCP Server 标识集合
     * @return 指定提供方当前可用的能力
     */
    public List<CapabilityDescriptor> ensureMcpProviders(Collection<String> providerIds) {
        Set<String> requested = providerIds == null ? Set.of() : new LinkedHashSet<>(providerIds);
        for (String providerId : requested) {
            boolean alreadyRegistered = capabilityRegistry.list().stream()
                    .anyMatch(capability -> providerId.equals(capability.providerId()));
            if (alreadyRegistered) {
                continue;
            }
            try {
                mcpInvocationService.refreshCapabilities(providerId);
            } catch (Exception error) {
                log.warn("MCP 能力发现失败，provider={}: {}", providerId, rootMessage(error));
            }
        }
        return capabilityRegistry.list().stream()
                .filter(CapabilityDescriptor::enabled)
                .filter(capability -> requested.contains(capability.providerId()))
                .toList();
    }

    /**
     * 确保全部已启用 MCP Server 完成工具发现。
     *
     * @return 全部 MCP 能力
     */
    public List<CapabilityDescriptor> ensureAllEnabledMcpProviders() {
        return ensureMcpProviders(mcpServerRegistry.listEnabled().stream().map(server -> server.id()).toList());
    }

    /**
     * 提取异常链最底层的可读信息。
     */
    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
