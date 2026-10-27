package com.melon.agent.capability;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.Map;
import java.util.Objects;

/**
 * Capability Registry 中保存的不可变能力描述。
 *
 * @param id 能力的全局唯一标识
 * @param type 能力类型
 * @param name 面向用户的能力名称
 * @param description 提供给模型的能力说明
 * @param inputSchema 输入参数 JSON Schema
 * @param outputSchema 输出结果 JSON Schema，可为空
 * @param modelToolName 暴露给模型的唯一工具名称
 * @param enabled 是否允许被 Agent 使用
 * @param approvalRequired 调用前是否要求人工批准
 * @param providerId 能力提供方或 MCP Server 标识
 * @param providerOperation 提供方内部的真实操作名称
 * @param metadata 扩展元数据
 * @param toolSpecification LangChain4j 工具规格，仅用于运行期，不通过接口序列化
 */
public record CapabilityDescriptor(
        String id,
        CapabilityType type,
        String name,
        String description,
        JsonNode inputSchema,
        JsonNode outputSchema,
        String modelToolName,
        boolean enabled,
        boolean approvalRequired,
        String providerId,
        String providerOperation,
        Map<String, String> metadata,
        @JsonIgnore ToolSpecification toolSpecification
) {
    /**
     * 校验必填字段并将元数据复制为不可变 Map。
     */
    public CapabilityDescriptor {
        Objects.requireNonNull(id, "capability id 不能为空");
        Objects.requireNonNull(type, "capability type 不能为空");
        Objects.requireNonNull(name, "capability name 不能为空");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
