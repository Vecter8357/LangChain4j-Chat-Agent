package com.melon.workflow.dag.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.melon.workflow.dag.model.DagWorkflowDefinition.NodeType;

import java.util.Map;
import java.util.Set;

public interface DagNodeExecutor {

    /** 声明执行器能够处理的 DAG 节点类型。 */
    Set<NodeType> supportedTypes();

    /** 使用已解析的输入执行一次节点调用并返回节点结果。 */
    DagNodeExecutionResult execute(DagNodeExecutionContext context);

    record DagNodeExecutionResult(JsonNode output, Map<String, Object> metrics) {
        /** 将节点输出包装成成功结果。 */
        public static DagNodeExecutionResult success(JsonNode output) {
            return new DagNodeExecutionResult(output, Map.of());
        }
    }
}
