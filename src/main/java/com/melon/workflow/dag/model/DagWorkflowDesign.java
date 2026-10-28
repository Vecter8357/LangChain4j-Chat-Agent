package com.melon.workflow.dag.model;

import lombok.Data;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可视化画布保存对象，将可执行定义与仅供 UI 使用的节点坐标分开存储。
 */
@Data
public class DagWorkflowDesign {

    private DagWorkflowDefinition definition;
    private DesignStatus status = DesignStatus.DRAFT;
    private Map<String, NodePosition> positions = new LinkedHashMap<>();
    private Instant createdAt;
    private Instant updatedAt;

    /** 工作流设计状态。 */
    public enum DesignStatus {
        DRAFT,
        PUBLISHED,
        DISABLED
    }

    /** 画布中的节点坐标，不参与运行时语义。 */
    public record NodePosition(double x, double y) {
    }
}
