package com.melon.controller;

import com.melon.agent.capability.CapabilityDescriptor;
import com.melon.agent.capability.CapabilityRegistry;
import com.melon.agent.runtime.AgentRun;
import com.melon.agent.runtime.AgentRunRequest;
import com.melon.agent.runtime.AgentRuntime;
import com.melon.config.properties.AgentPlatformProperties.McpServerProperties;
import com.melon.mcp.McpInvocationService;
import com.melon.mcp.McpServerDefinition;
import com.melon.mcp.McpServerRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 平台管理与执行 API，提供能力查询、MCP 刷新和 Agent Run 启动接口。
 */
@RestController
@RequestMapping("/api/agent-platform")
public class AgentPlatformController {

    private final AgentRuntime agentRuntime;
    private final CapabilityRegistry capabilityRegistry;
    private final McpInvocationService mcpInvocationService;
    private final McpServerRegistry mcpServerRegistry;

    /**
     * 创建控制器并注入运行时、能力目录及 MCP 服务。
     */
    public AgentPlatformController(AgentRuntime agentRuntime,
                                   CapabilityRegistry capabilityRegistry,
                                   McpInvocationService mcpInvocationService,
                                   McpServerRegistry mcpServerRegistry) {
        this.agentRuntime = agentRuntime;
        this.capabilityRegistry = capabilityRegistry;
        this.mcpInvocationService = mcpInvocationService;
        this.mcpServerRegistry = mcpServerRegistry;
    }

    /**
     * 查询当前已注册能力。
     *
     * @return 能力列表
     */
    @GetMapping("/capabilities")
    public List<CapabilityDescriptor> capabilities() {
        return capabilityRegistry.list();
    }

    /**
     * 查询已启用 MCP Server 的非敏感摘要。
     *
     * @return 服务摘要列表
     */
    @GetMapping("/mcp/servers")
    public List<McpServerView> mcpServers() {
        return mcpServerRegistry.listEnabled().stream().map(this::view).toList();
    }

    /**
     * 刷新一个 MCP Server 的 Tool 列表。
     *
     * @param serverId MCP Server 标识
     * @return 发现并注册的能力
     */
    @PostMapping("/mcp/servers/{serverId}/refresh")
    public ResponseEntity<List<CapabilityDescriptor>> refresh(@PathVariable String serverId) {
        return ResponseEntity.ok(mcpInvocationService.refreshCapabilities(serverId));
    }

    /**
     * 刷新全部已启用 MCP Server。
     *
     * @return 所有发现的能力
     */
    @PostMapping("/mcp/refresh")
    public ResponseEntity<List<CapabilityDescriptor>> refreshAll() {
        List<CapabilityDescriptor> result = new ArrayList<>();
        for (McpServerDefinition server : mcpServerRegistry.listEnabled()) {
            result.addAll(mcpInvocationService.refreshCapabilities(server.id()));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 同步启动一次 Agent Run。
     *
     * @param request Agent 运行请求
     * @return 完整运行结果
     */
    @PostMapping("/runs")
    public ResponseEntity<AgentRun> run(@RequestBody AgentRunRequest request) {
        return ResponseEntity.ok(agentRuntime.execute(request));
    }

    /**
     * 将服务定义转换为不会泄露命令环境变量和认证头的视图。
     */
    private McpServerView view(McpServerDefinition definition) {
        McpServerProperties configuration = definition.configuration();
        return new McpServerView(definition.id(), configuration.getTransport().name(),
                configuration.isEnabled(), configuration.getTimeout().toString());
    }

    /**
     * 对外展示的 MCP Server 安全摘要。
     */
    public record McpServerView(String id, String transport, boolean enabled, String timeout) {
    }
}
