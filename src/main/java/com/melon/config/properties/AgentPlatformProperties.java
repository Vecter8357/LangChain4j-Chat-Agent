package com.melon.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent Runtime 与 MCP 平台的外部化配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent-platform")
public class AgentPlatformProperties {

    private RuntimeProperties runtime = new RuntimeProperties();

    private McpProperties mcp = new McpProperties();

    /**
     * Agent Runtime 的执行限制配置。
     */
    @Data
    public static class RuntimeProperties {

        private boolean enabled = true;

        private int defaultMaxSteps = 8;

        private int maximumMaxSteps = 30;

        private int maximumCapabilities = 64;
    }

    /**
     * MCP Server 定义集合。
     */
    @Data
    public static class McpProperties {

        private Map<String, McpServerProperties> servers = new LinkedHashMap<>();
    }

    /**
     * 单个 MCP Server 的传输、超时、健康检查和认证头配置。
     */
    @Data
    public static class McpServerProperties {

        private boolean enabled = true;

        private Transport transport = Transport.STDIO;

        private java.util.List<String> command = new java.util.ArrayList<>();

        private Map<String, String> environment = new LinkedHashMap<>();

        private String workingDirectory;

        private String url;

        private Map<String, String> headers = new LinkedHashMap<>();

        private Duration initializationTimeout = Duration.ofSeconds(30);

        private Duration timeout = Duration.ofSeconds(120);

        private Duration reconnectInterval = Duration.ofSeconds(5);

        private boolean debug;

        private boolean autoHealthCheck = true;

        private Duration healthCheckInterval = Duration.ofSeconds(30);

        /**
         * 平台支持的 MCP 传输方式。
         */
        public enum Transport {
            STDIO,
            STREAMABLE_HTTP
        }
    }
}
