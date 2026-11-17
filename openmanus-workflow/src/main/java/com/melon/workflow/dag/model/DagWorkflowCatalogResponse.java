package com.melon.workflow.dag.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.melon.agent.capability.CapabilityDescriptor;

import java.util.List;

/**
 * 可视化设计器初始化所需的节点、模型角色、条件和能力目录。
 */
public record DagWorkflowCatalogResponse(
        List<NodeTemplate> nodeTypes,
        List<String> modelRoles,
        List<String> conditionOperators,
        List<String> joinModes,
        List<CapabilityDescriptor> capabilities
) {
    /** 单个可拖入画布的节点模板。 */
    public record NodeTemplate(String type,
                               String label,
                               String description,
                               JsonNode defaultConfig) {
    }
}
