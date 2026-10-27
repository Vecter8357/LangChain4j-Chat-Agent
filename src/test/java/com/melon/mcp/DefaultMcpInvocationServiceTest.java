package com.melon.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.agent.capability.InMemoryCapabilityRegistry;
import com.melon.config.properties.AgentPlatformProperties;
import com.melon.config.properties.AgentPlatformProperties.McpServerProperties;
import com.melon.config.properties.RewriteWorkflowProperties;
import com.melon.config.properties.ToolSecurityProperties;
import com.melon.workflow.dag.engine.DagJsonSchemaValidator;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultMcpInvocationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void discoversRegistersValidatesAndInvokesMcpTool() {
        AgentPlatformProperties properties = new AgentPlatformProperties();
        properties.getMcp().getServers().put("search", new McpServerProperties());
        McpServerRegistry serverRegistry = serverRegistry(properties);
        InMemoryCapabilityRegistry capabilityRegistry = new InMemoryCapabilityRegistry();
        McpConnectionManager connectionManager = mock(McpConnectionManager.class);
        McpClient client = mock(McpClient.class);
        when(connectionManager.get("search")).thenReturn(client);
        when(client.listTools()).thenReturn(List.of(ToolSpecification.builder()
                .name("query")
                .description("search the web")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("query")
                        .required("query")
                        .build())
                .build()));
        when(client.executeTool(any())).thenReturn(ToolExecutionResult.builder()
                .isError(false)
                .resultText("{\"answer\":\"ok\"}")
                .build());
        DefaultMcpInvocationService service = new DefaultMcpInvocationService(
                connectionManager, serverRegistry, capabilityRegistry,
                new DagJsonSchemaValidator(), objectMapper, disabledLegacyTools());

        var capabilities = service.refreshCapabilities("search");
        var result = service.invoke(new McpInvocationRequest("run-1", "agent-1",
                capabilities.getFirst().id(), objectMapper.createObjectNode().put("query", "LangChain4j")));

        assertThat(capabilities).hasSize(1);
        assertThat(capabilities.getFirst().modelToolName()).isEqualTo("mcp__search__query");
        assertThat(result.successful()).isTrue();
        assertThat(result.content().path("answer").asText()).isEqualTo("ok");
        ArgumentCaptor<ToolExecutionRequest> captor = ArgumentCaptor.forClass(ToolExecutionRequest.class);
        verify(client).executeTool(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("query");
    }

    @Test
    void rejectsArgumentsThatDoNotMatchDiscoveredSchema() {
        AgentPlatformProperties properties = new AgentPlatformProperties();
        properties.getMcp().getServers().put("search", new McpServerProperties());
        McpServerRegistry serverRegistry = serverRegistry(properties);
        InMemoryCapabilityRegistry capabilityRegistry = new InMemoryCapabilityRegistry();
        McpConnectionManager connectionManager = mock(McpConnectionManager.class);
        McpClient client = mock(McpClient.class);
        when(connectionManager.get("search")).thenReturn(client);
        when(client.listTools()).thenReturn(List.of(ToolSpecification.builder()
                .name("query")
                .parameters(JsonObjectSchema.builder().addStringProperty("query").required("query").build())
                .build()));
        DefaultMcpInvocationService service = new DefaultMcpInvocationService(
                connectionManager, serverRegistry, capabilityRegistry,
                new DagJsonSchemaValidator(), objectMapper, disabledLegacyTools());
        var capability = service.refreshCapabilities("search").getFirst();

        assertThatThrownBy(() -> service.invoke(new McpInvocationRequest(
                "run-1", "agent-1", capability.id(), objectMapper.createObjectNode())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query 为必填字段");
    }

    @Test
    void enforcesFetchDomainPolicyAtUnifiedInvocationBoundary() {
        AgentPlatformProperties properties = new AgentPlatformProperties();
        properties.getMcp().getServers().put("fetch", new McpServerProperties());
        ToolSecurityProperties security = new ToolSecurityProperties();
        security.getGithub().setEnabled(false);
        security.getFetch().setAllowedDomains(List.of("example.com"));
        McpServerRegistry serverRegistry = new McpServerRegistry(
                properties, security, new RewriteWorkflowProperties());
        InMemoryCapabilityRegistry capabilityRegistry = new InMemoryCapabilityRegistry();
        McpConnectionManager connectionManager = mock(McpConnectionManager.class);
        McpClient client = mock(McpClient.class);
        when(connectionManager.get("fetch")).thenReturn(client);
        when(client.listTools()).thenReturn(List.of(ToolSpecification.builder()
                .name("fetch")
                .parameters(JsonObjectSchema.builder().addStringProperty("url").required("url").build())
                .build()));
        DefaultMcpInvocationService service = new DefaultMcpInvocationService(
                connectionManager, serverRegistry, capabilityRegistry,
                new DagJsonSchemaValidator(), objectMapper, security);
        var capability = service.refreshCapabilities("fetch").getFirst();

        assertThatThrownBy(() -> service.invoke(new McpInvocationRequest(
                "run-1", "agent-1", capability.id(),
                objectMapper.createObjectNode().put("url", "https://evil.invalid/page"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("域名不在允许范围");
    }

    private McpServerRegistry serverRegistry(AgentPlatformProperties properties) {
        ToolSecurityProperties toolSecurity = disabledLegacyTools();
        return new McpServerRegistry(properties, toolSecurity, new RewriteWorkflowProperties());
    }

    private ToolSecurityProperties disabledLegacyTools() {
        ToolSecurityProperties toolSecurity = new ToolSecurityProperties();
        toolSecurity.getGithub().setEnabled(false);
        toolSecurity.getFetch().setEnabled(false);
        return toolSecurity;
    }
}
