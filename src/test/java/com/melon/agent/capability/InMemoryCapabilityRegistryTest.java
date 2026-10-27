package com.melon.agent.capability;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCapabilityRegistryTest {

    @Test
    void registersFiltersAndRemovesCapabilitiesByProvider() {
        InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();
        CapabilityDescriptor capability = capability("mcp.search.query", "mcp__search__query", "search");

        registry.register(capability);

        assertThat(registry.findById(capability.id())).contains(capability);
        assertThat(registry.findByModelToolName(capability.modelToolName())).contains(capability);
        assertThat(registry.findEnabled(List.of(capability.id()))).containsExactly(capability);

        registry.removeByProvider("search");

        assertThat(registry.list()).isEmpty();
        assertThat(registry.findByModelToolName(capability.modelToolName())).isEmpty();
    }

    private CapabilityDescriptor capability(String id, String toolName, String provider) {
        ToolSpecification specification = ToolSpecification.builder()
                .name(toolName)
                .description("search")
                .parameters(JsonObjectSchema.builder().addStringProperty("query").required("query").build())
                .build();
        return new CapabilityDescriptor(id, CapabilityType.MCP_TOOL, "query", "search", null, null,
                toolName, true, false, provider, "query", Map.of(), specification);
    }
}
