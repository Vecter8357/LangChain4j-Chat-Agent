package com.melon.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.melon.agent.capability.CapabilityDescriptor;
import com.melon.agent.capability.CapabilityType;
import com.melon.agent.capability.InMemoryCapabilityRegistry;
import com.melon.config.properties.AgentPlatformProperties;
import com.melon.workflow.dag.engine.DagJsonSchemaValidator;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class DefaultAgentRuntimeTest {

    @Test
    void rejectsKnowledgeToolsThatExpandInputBaseOrDocumentScope() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String arguments : List.of(
                "{\"question\":\"q\",\"knowledgeBaseIds\":[\"another-base\"],\"fileIds\":[\"allowed-doc\"]}",
                "{\"question\":\"q\",\"knowledgeBaseIds\":[\"allowed-base\"],\"fileIds\":[\"another-doc\"]}")) {
            ChatModel model = mock(ChatModel.class);
            var tool = ToolExecutionRequest.builder().id("search-1").name("knowledge__search").arguments(arguments).build();
            when(model.chat(any(ChatRequest.class)))
                    .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(tool)).build())
                    .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("无法访问超出范围的来源")).build());
            var registry = new InMemoryCapabilityRegistry();
            var specification = ToolSpecification.builder().name("knowledge__search").description("search")
                    .parameters(JsonObjectSchema.builder().build()).build();
            registry.register(new CapabilityDescriptor("knowledge.search",CapabilityType.KNOWLEDGE,"search","search",null,null,
                    specification.name(),true,false,"knowledge","search",Map.of(),specification));
            CapabilityInvoker invoker = mock(CapabilityInvoker.class);
            when(invoker.supportedTypes()).thenReturn(Set.of(CapabilityType.KNOWLEDGE));
            var runtime = new DefaultAgentRuntime(model,registry,new CapabilityInvokerRegistry(List.of(invoker)),
                    new AgentPlatformProperties(),mapper,new DagJsonSchemaValidator());
            AgentDefinition definition = new AgentDefinition();definition.setId("test");definition.setSystemPrompt("search");
            definition.setCapabilityIds(Set.of("knowledge.search"));definition.setMaxSteps(2);
            AgentRunRequest request = new AgentRunRequest();request.setDefinition(definition);request.setObjective("search");
            request.setInput(mapper.readTree("{\"knowledgeBaseIds\":[\"allowed-base\"],\"knowledgeFileIds\":[\"allowed-doc\"]}"));
            var run = runtime.execute(request);
            assertThat(run.getSteps().get(1).isSuccessful()).isFalse();
            assertThat(run.getSteps().get(1).getErrorMessage()).contains("超出");
            verify(invoker,never()).invoke(any());
        }
    }

    @Test
    void performsModelToolObservationLoopUntilFinalAnswer() {
        ObjectMapper objectMapper = new ObjectMapper();
        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("mcp__search__query")
                .arguments("{\"query\":\"agent runtime\"}")
                .build();
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(toolRequest)).build())
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("综合工具结果后的最终答案")).build());

        InMemoryCapabilityRegistry capabilityRegistry = new InMemoryCapabilityRegistry();
        ToolSpecification specification = ToolSpecification.builder()
                .name("mcp__search__query")
                .description("search")
                .parameters(JsonObjectSchema.builder().addStringProperty("query").required("query").build())
                .build();
        capabilityRegistry.register(new CapabilityDescriptor(
                "mcp.search.query", CapabilityType.MCP_TOOL, "query", "search", null, null,
                specification.name(), true, false, "search", "query", Map.of(), specification));
        CapabilityInvoker invoker = new CapabilityInvoker() {
            @Override
            public Set<CapabilityType> supportedTypes() {
                return Set.of(CapabilityType.MCP_TOOL);
            }

            @Override
            public CapabilityInvocation.Result invoke(CapabilityInvocation.Request request) {
                return new CapabilityInvocation.Result(true, TextNode.valueOf("tool evidence"),
                        null, null, false, 12);
            }
        };
        DefaultAgentRuntime runtime = new DefaultAgentRuntime(model, capabilityRegistry,
                new CapabilityInvokerRegistry(List.of(invoker)), new AgentPlatformProperties(), objectMapper,
                new DagJsonSchemaValidator());
        AgentDefinition definition = new AgentDefinition();
        definition.setId("research-agent");
        definition.setSystemPrompt("Research and answer.");
        definition.setCapabilityIds(Set.of("mcp.search.query"));
        definition.setMaxSteps(4);
        AgentRunRequest request = new AgentRunRequest();
        request.setDefinition(definition);
        request.setObjective("研究 Agent Runtime");

        AgentRun run = runtime.execute(request);

        assertThat(run.getStatus()).isEqualTo(AgentRun.Status.COMPLETED);
        assertThat(run.getFinalAnswer()).isEqualTo("综合工具结果后的最终答案");
        assertThat(run.getSteps()).hasSize(3);
        assertThat(run.getSteps().get(1).getCapabilityId()).isEqualTo("mcp.search.query");
        assertThat(run.getSteps().get(1).getObservation().asText()).isEqualTo("tool evidence");
    }
}
