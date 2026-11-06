package com.melon.knowledge;

import com.melon.agent.capability.CapabilityDescriptor;
import com.melon.agent.capability.CapabilityRegistry;
import com.melon.agent.capability.CapabilityType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 在应用启动时注册临时附件和永久知识库能力。
 */
@Component
public class KnowledgeCapabilityRegistrar {

    public static final String RAG_ANSWER = "knowledge.rag.answer";
    public static final String KNOWLEDGE_SEARCH = "knowledge.search";

    private final CapabilityRegistry registry;
    private final ObjectMapper objectMapper;

    /**
     * 创建内置能力注册器。
     */
    public KnowledgeCapabilityRegistrar(CapabilityRegistry registry,
                                      ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    /**
     * 注册只读检索能力。
     */
    @PostConstruct
    public void register() {
        ToolSpecification rag = ToolSpecification.builder()
                .name("knowledge__rag__answer")
                .description("查询用户已上传的文档。只有当前请求提供了 fileIds 时才可使用。")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("question", "需要根据文档回答的问题")
                        .addProperty("fileIds", JsonArraySchema.builder()
                                .description("当前请求允许查询的文件ID")
                                .items(new JsonStringSchema())
                                .build())
                        .required("question", "fileIds")
                        .build())
                .build();
        registry.register(descriptor(RAG_ANSWER, CapabilityType.KNOWLEDGE,
                "文档知识库问答", "knowledge", "answer", rag));
        ToolSpecification search = ToolSpecification.builder().name("knowledge__search")
                .description("检索已选择的永久知识库，返回混合检索、重排和带版本的引用来源")
                .parameters(JsonObjectSchema.builder().addStringProperty("question")
                        .addProperty("knowledgeBaseIds", JsonArraySchema.builder().items(new JsonStringSchema()).build())
                        .addProperty("fileIds", JsonArraySchema.builder().items(new JsonStringSchema()).build())
                        .required("question", "knowledgeBaseIds").build()).build();
        registry.register(descriptor(KNOWLEDGE_SEARCH, CapabilityType.KNOWLEDGE,
                "永久知识库检索", "knowledge", "search", search));
    }

    /**
     * 将 LangChain4j 工具规格转换为统一能力描述。
     */
    private CapabilityDescriptor descriptor(String id,
                                            CapabilityType type,
                                            String description,
                                            String provider,
                                            String operation,
                                            ToolSpecification specification) {
        return new CapabilityDescriptor(id, type, description, description,
                schema(specification), null, specification.name(), true, false,
                provider, operation, Map.of(), specification);
    }

    /**
     * 从工具规格中提取参数 JSON Schema。
     */
    private JsonNode schema(ToolSpecification specification) {
        try {
            return objectMapper.readTree(specification.toJson()).path("parameters");
        } catch (Exception error) {
            throw new IllegalStateException("内置能力 Schema 生成失败: " + specification.name(), error);
        }
    }
}
