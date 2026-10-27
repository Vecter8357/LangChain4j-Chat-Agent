package com.melon.mcp;

import com.melon.config.properties.AgentPlatformProperties.McpServerProperties;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认 MCP 连接管理器，按 serverId 缓存并复用 stdio 或 Streamable HTTP 客户端。
 */
@Component
public class DefaultMcpConnectionManager implements McpConnectionManager {

    private final McpServerRegistry serverRegistry;
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();

    /**
     * 创建连接管理器。
     */
    public DefaultMcpConnectionManager(McpServerRegistry serverRegistry) {
        this.serverRegistry = serverRegistry;
    }

    /** {@inheritDoc} */
    @Override
    public McpClient get(String serverId) {
        return clients.computeIfAbsent(serverId, ignored -> create(serverRegistry.require(serverId)));
    }

    /** {@inheritDoc} */
    @Override
    public void invalidate(String serverId) {
        McpClient client = clients.remove(serverId);
        close(client);
    }

    /**
     * {@inheritDoc}
     * 健康检查失败时移除缓存，确保下一次调用能够重建连接。
     */
    @Override
    public void checkHealth(String serverId) {
        try {
            get(serverId).checkHealth();
        } catch (RuntimeException error) {
            invalidate(serverId);
            throw error;
        }
    }

    /**
     * 根据服务定义创建配置了超时、缓存和健康检查的 MCP Client。
     */
    private McpClient create(McpServerDefinition definition) {
        McpServerProperties configuration = definition.configuration();
        McpTransport transport = switch (configuration.getTransport()) {
            case STDIO -> stdio(configuration);
            case STREAMABLE_HTTP -> streamableHttp(configuration);
        };
        return new DefaultMcpClient.Builder()
                .key(definition.id())
                .clientName("langchain4j-chat-agent")
                .transport(transport)
                .initializationTimeout(configuration.getInitializationTimeout())
                .toolExecutionTimeout(configuration.getTimeout())
                .reconnectInterval(configuration.getReconnectInterval())
                .autoHealthCheck(configuration.isAutoHealthCheck())
                .autoHealthCheckInterval(configuration.getHealthCheckInterval())
                .cacheToolList(true)
                .subscribeToToolListChanges(true)
                .build();
    }

    /**
     * 创建本地子进程 stdio 传输。
     */
    private McpTransport stdio(McpServerProperties configuration) {
        if (configuration.getCommand() == null || configuration.getCommand().isEmpty()) {
            throw new IllegalArgumentException("STDIO MCP Server 必须配置 command");
        }
        StdioMcpTransport.Builder builder = new StdioMcpTransport.Builder()
                .command(configuration.getCommand())
                .environment(configuration.getEnvironment())
                .logEvents(configuration.isDebug());
        if (StringUtils.hasText(configuration.getWorkingDirectory())) {
            builder.workingDirectory(Path.of(configuration.getWorkingDirectory()).toAbsolutePath().normalize());
        }
        return builder.build();
    }

    /**
     * 创建远程 Streamable HTTP 传输。
     */
    private McpTransport streamableHttp(McpServerProperties configuration) {
        if (!StringUtils.hasText(configuration.getUrl())) {
            throw new IllegalArgumentException("Streamable HTTP MCP Server 必须配置 url");
        }
        return new StreamableHttpMcpTransport.Builder()
                .url(configuration.getUrl())
                .customHeaders(configuration.getHeaders())
                .timeout(configuration.getTimeout())
                .logRequests(configuration.isDebug())
                .logResponses(configuration.isDebug())
                .build();
    }

    /**
     * 应用关闭时释放所有 MCP 客户端及其底层资源。
     */
    @PreDestroy
    public void closeAll() {
        clients.values().forEach(this::close);
        clients.clear();
    }

    /**
     * 安静关闭单个客户端，避免清理异常阻止应用退出。
     */
    private void close(McpClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception ignored) {
            // 关闭连接不应阻止应用退出或连接重建。
        }
    }
}
