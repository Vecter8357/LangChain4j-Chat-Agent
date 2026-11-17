package com.melon.workflow.dag.model;

import java.util.List;

/**
 * 面向可视化设计器的工作流校验结果。
 *
 * @param valid 是否通过校验
 * @param workflowCode 工作流编码
 * @param workflowVersion 工作流版本
 * @param topologicalOrder 校验成功时的拓扑顺序
 * @param errors 可定位到节点、边或工作流字段的错误
 * @param warnings 不阻断运行的提示
 */
public record DagWorkflowValidationResponse(
        boolean valid,
        String workflowCode,
        int workflowVersion,
        List<String> topologicalOrder,
        List<ValidationIssue> errors,
        List<ValidationIssue> warnings
) {
    /**
     * 单条结构化校验问题。
     *
     * @param scope WORKFLOW、NODE 或 EDGE
     * @param targetId 画布元素标识
     * @param field 可能出错的配置字段
     * @param code 稳定错误码
     * @param message 用户可读信息
     */
    public record ValidationIssue(String scope,
                                  String targetId,
                                  String field,
                                  String code,
                                  String message) {
    }
}
