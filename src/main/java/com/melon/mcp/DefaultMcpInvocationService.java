package com.melon.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.melon.agent.capability.CapabilityDescriptor;
import com.melon.agent.capability.CapabilityRegistry;
import com.melon.agent.capability.CapabilityType;
import com.melon.config.properties.ToolSecurityProperties;
import com.melon.workflow.dag.engine.DagJsonSchemaValidator;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.net.URI;

/**
 * MCP Invocation Service 是平台调用 MCP 的统一入口。
 *
 * 它解决的是：
 * 已经确定要调用某个 MCP Tool 后，如何安全、稳定、可追踪地完成调用
 */
/**
 * MCP Invocation Service 默认实现。
 * 统一完成工具发现、能力注册、Schema 校验、安全策略、调用和错误标准化。
 */
@Service
public class DefaultMcpInvocationService implements McpInvocationService {

    private final McpConnectionManager connectionManager;
    private final McpServerRegistry serverRegistry;
    private final CapabilityRegistry capabilityRegistry;
    private final DagJsonSchemaValidator schemaValidator;
    private final ObjectMapper objectMapper;
    private final ToolSecurityProperties securityProperties;

    /**
     * 创建 MCP 调用服务。
     */
    public DefaultMcpInvocationService(McpConnectionManager connectionManager,
                                       McpServerRegistry serverRegistry,
                                       CapabilityRegistry capabilityRegistry,
                                       DagJsonSchemaValidator schemaValidator,
                                       ObjectMapper objectMapper,
                                       ToolSecurityProperties securityProperties) {
        this.connectionManager = connectionManager;
        this.serverRegistry = serverRegistry;
        this.capabilityRegistry = capabilityRegistry;
        this.schemaValidator = schemaValidator;
        this.objectMapper = objectMapper;
        this.securityProperties = securityProperties;
    }

    /**
     * {@inheritDoc}
     * MCP 原始工具名会转换为模型可安全使用的全局唯一名称。
     */
    @Override
    public List<CapabilityDescriptor> refreshCapabilities(String serverId) {
        serverRegistry.require(serverId);
        McpClient client = connectionManager.get(serverId);
        List<ToolSpecification> tools = client.listTools();
        List<CapabilityDescriptor> capabilities = new ArrayList<>();
        for (ToolSpecification tool : tools) {
            String capabilityId = "mcp." + serverId + "." + tool.name();
            String exposedName = modelToolName(serverId, tool.name());
            ToolSpecification exposedSpecification = ToolSpecification.builder()
                    .name(exposedName)
                    .description(tool.description())
                    .parameters(tool.parameters())
                    .metadata(tool.metadata())
                    .strict(tool.strict())
                    .build();
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("serverId", serverId);
            metadata.put("toolName", tool.name());
            JsonNode inputSchema = inputSchema(tool);
            CapabilityDescriptor capability = new CapabilityDescriptor(
                    capabilityId,
                    CapabilityType.MCP_TOOL,
                    tool.name(),
                    tool.description(),
                    inputSchema,
                    null,
                    exposedName,
                    true,
                    false,
                    serverId,
                    tool.name(),
                    metadata,
                    exposedSpecification);
            capabilities.add(capability);
        }
        capabilityRegistry.removeByProvider(serverId);
        capabilities.forEach(capabilityRegistry::register);
        return List.copyOf(capabilities);
    }

    /**
     * {@inheritDoc}
     * 调用前统一执行能力类型、启用状态、JSON Schema 和安全白名单校验。
     */
    @Override
    public McpInvocationResult invoke(McpInvocationRequest request) {
        if (request == null || !StringUtils.hasText(request.capabilityId())) {
            throw new IllegalArgumentException("MCP capabilityId 不能为空");
        }
        CapabilityDescriptor capability = capabilityRegistry.findById(request.capabilityId())
                .orElseThrow(() -> new IllegalArgumentException("未注册能力: " + request.capabilityId()));
        if (capability.type() != CapabilityType.MCP_TOOL) {
            throw new IllegalArgumentException("能力不是 MCP Tool: " + request.capabilityId());
        }
        if (!capability.enabled()) {
            throw new IllegalStateException("能力已禁用: " + request.capabilityId());
        }

        JsonNode arguments = request.arguments() == null ? objectMapper.createObjectNode() : request.arguments();
        schemaValidator.validate(arguments, capability.inputSchema(),
                "MCP Tool " + capability.id() + " 输入");
        validateSecurityPolicy(capability, arguments);

        Instant startedAt = Instant.now();
        try {
            McpClient client = connectionManager.get(capability.providerId());
            ToolExecutionRequest executionRequest = ToolExecutionRequest.builder()
                    .id(UUID.randomUUID().toString())
                    .name(capability.providerOperation())
                    .arguments(objectMapper.writeValueAsString(arguments))
                    .build();
            ToolExecutionResult toolResult = client.executeTool(executionRequest);
            long durationMillis = Duration.between(startedAt, Instant.now()).toMillis();
            if (toolResult.isError()) {
                return McpInvocationResult.failure(capability.id(), capability.providerId(),
                        capability.providerOperation(), "MCP_TOOL_ERROR", toolResult.resultText(), false,
                        durationMillis);
            }
            return McpInvocationResult.success(capability.id(), capability.providerId(),
                    capability.providerOperation(), limitContent(capability, content(toolResult)), durationMillis);
        } catch (Exception error) {
            connectionManager.invalidate(capability.providerId());
            long durationMillis = Duration.between(startedAt, Instant.now()).toMillis();
            return McpInvocationResult.failure(capability.id(), capability.providerId(),
                    capability.providerOperation(), "MCP_INVOCATION_FAILED", rootMessage(error),
                    retryable(error), durationMillis);
        }
    }

    /**
     * 从 LangChain4j ToolSpecification 提取 JSON Schema。
     */
    private JsonNode inputSchema(ToolSpecification specification) {
        if (specification == null) {
            return NullNode.instance;
        }
        try {
            return objectMapper.readTree(specification.toJson()).path("parameters");
        } catch (Exception error) {
            throw new IllegalArgumentException("MCP Tool Schema 无法解析: " + specification.name(), error);
        }
    }

    /**
     * 将 MCP 工具结果转换为 JsonNode，保留结构化 JSON，普通文本使用 TextNode。
     */
    private JsonNode content(ToolExecutionResult result) {
        Object value = result.result();
        if (value != null) {
            return objectMapper.valueToTree(value);
        }
        String text = result.resultText();
        if (!StringUtils.hasText(text)) {
            return NullNode.instance;
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception ignored) {
            return TextNode.valueOf(text);
        }
    }

    /**
     * 生成符合模型工具命名限制且包含服务命名空间的名称。
     */
    private String modelToolName(String serverId, String toolName) {
        String normalizedServer = normalize(serverId);
        String normalizedTool = normalize(toolName);
        String value = "mcp__" + normalizedServer + "__" + normalizedTool;
        return value.length() <= 64 ? value : value.substring(0, 55) + "_" + Integer.toHexString(value.hashCode());
    }

    /**
     * 根据能力提供方执行集中式安全策略。
     */
    private void validateSecurityPolicy(CapabilityDescriptor capability, JsonNode arguments) {
        if ("fetch".equals(capability.providerId())) {
            validateFetch(arguments);
        }
        if ("github".equals(capability.providerId())) {
            validateGitHub(arguments);
        }
    }

    /**
     * 校验 Fetch 参数中的 URL 是否命中允许域名。
     */
    private void validateFetch(JsonNode arguments) {
        List<String> allowedDomains = securityProperties.getFetch().getAllowedDomains().stream()
                .filter(StringUtils::hasText)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .toList();
        if (allowedDomains.isEmpty()) {
            return;
        }
        List<String> urls = new ArrayList<>();
        collectHttpUrls(arguments, urls);
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("Fetch MCP 调用被拒绝：启用域名白名单时必须提供明确 URL");
        }
        for (String url : urls) {
            String host = URI.create(url).getHost();
            String normalizedHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
            boolean allowed = allowedDomains.stream()
                    .anyMatch(domain -> normalizedHost.equals(domain) || normalizedHost.endsWith("." + domain));
            if (!allowed) {
                throw new IllegalArgumentException("Fetch MCP 调用被拒绝，域名不在允许范围: " + url);
            }
        }
    }

    /**
     * 校验 GitHub 参数是否命中允许仓库范围。
     */
    private void validateGitHub(JsonNode arguments) {
        List<String> allowedRepositories = securityProperties.getGithub().getAllowedRepositories().stream()
                .filter(StringUtils::hasText)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .toList();
        if (allowedRepositories.isEmpty()) {
            return;
        }
        String serialized = arguments.toString().toLowerCase(Locale.ROOT);
        String owner = arguments.path("owner").asText("").toLowerCase(Locale.ROOT);
        String repository = arguments.path("repo").asText(
                arguments.path("repository").asText("")).toLowerCase(Locale.ROOT);
        String fullName = owner.isBlank() || repository.isBlank() ? "" : owner + "/" + repository;
        boolean allowed = allowedRepositories.stream()
                .anyMatch(item -> item.equals(fullName) || serialized.contains(item));
        if (!allowed) {
            throw new IllegalArgumentException("GitHub MCP 调用被拒绝：参数未命中允许仓库范围");
        }
    }

    /**
     * 递归收集工具参数中的 HTTP/HTTPS URL。
     */
    private void collectHttpUrls(JsonNode node, List<String> urls) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            String text = node.asText();
            if (text.startsWith("http://") || text.startsWith("https://")) {
                urls.add(text);
            }
            return;
        }
        node.elements().forEachRemaining(child -> collectHttpUrls(child, urls));
    }

    /**
     * 对 Fetch 结果应用统一最大长度限制。
     */
    private JsonNode limitContent(CapabilityDescriptor capability, JsonNode content) {
        if (!"fetch".equals(capability.providerId()) || content == null) {
            return content;
        }
        int maximum = securityProperties.getFetch().getMaxResponseLength();
        String serialized = content.isTextual() ? content.asText() : content.toString();
        if (maximum <= 0 || serialized.length() <= maximum) {
            return content;
        }
        return TextNode.valueOf(serialized.substring(0, maximum)
                + "\n\n[内容过长，已按工具安全配置截断]");
    }

    /**
     * 把任意标识规范化为模型工具名可接受的字符集合。
     */
    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    /**
     * 根据异常类型和消息判断调用是否适合重试。
     */
    private boolean retryable(Throwable error) {
        String name = error.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        String message = rootMessage(error).toLowerCase(Locale.ROOT);
        return name.contains("timeout") || name.contains("connect")
                || message.contains("timeout") || message.contains("connection");
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
