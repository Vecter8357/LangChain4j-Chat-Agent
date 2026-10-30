package com.melon.agent.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.config.properties.ToolSecurityProperties;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 在应用启动时把安全的本地文件系统操作和文档检索注册为平台能力。
 */
@Component
public class BuiltInCapabilityRegistrar {

    public static final String RAG_ANSWER = "knowledge.rag.answer";
    public static final String KNOWLEDGE_SEARCH = "knowledge.search";

    private final CapabilityRegistry registry;
    private final ToolSecurityProperties securityProperties;
    private final ObjectMapper objectMapper;

    /**
     * 创建内置能力注册器。
     */
    public BuiltInCapabilityRegistrar(CapabilityRegistry registry,
                                      ToolSecurityProperties securityProperties,
                                      ObjectMapper objectMapper) {
        this.registry = registry;
        this.securityProperties = securityProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 注册当前安全配置允许的内置能力；写入和删除操作不会自动暴露。
     */
    @PostConstruct
    public void register() {
        if (securityProperties.getFilesystem().isEnabled()) {
            registerFilesystemCapabilities();
        }
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
     * 注册只读文件系统能力。
     */
    private void registerFilesystemCapabilities() {
        registerFilesystem("local.filesystem.list_directory", "filesystem__list_directory",
                "列出允许目录中的文件和子目录", "listDirectory",
                JsonObjectSchema.builder().addStringProperty("dirPath").required("dirPath").build());
        registerFilesystem("local.filesystem.read_file", "filesystem__read_file",
                "读取允许目录中的文本文件", "readFile",
                JsonObjectSchema.builder().addStringProperty("filePath").required("filePath").build());
        registerFilesystem("local.filesystem.search_files", "filesystem__search_files",
                "按文件名关键字搜索允许目录", "searchFiles",
                JsonObjectSchema.builder()
                        .addStringProperty("dirPath")
                        .addStringProperty("keyword")
                        .required("dirPath", "keyword")
                        .build());
        registerFilesystem("local.filesystem.info", "filesystem__info",
                "查看允许目录所在文件系统的空间信息", "getFilesystemInfo",
                JsonObjectSchema.builder().build());
    }

    /**
     * 构造并注册一个文件系统能力。
     */
    private void registerFilesystem(String id,
                                    String modelName,
                                    String description,
                                    String operation,
                                    JsonObjectSchema parameters) {
        ToolSpecification specification = ToolSpecification.builder()
                .name(modelName)
                .description(description)
                .parameters(parameters)
                .build();
        registry.register(descriptor(id, CapabilityType.LOCAL_TOOL,
                description, "filesystem", operation, specification));
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
