package com.melon.workflow.dag.executor;

import com.melon.workflow.dag.model.DagWorkflowDefinition.NodeType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class DagNodeExecutorRegistry {

    private final Map<NodeType, DagNodeExecutor> executors = new EnumMap<>(NodeType.class);

    /** 按节点类型注册执行器，并拒绝重复的类型绑定。 */
    public DagNodeExecutorRegistry(List<DagNodeExecutor> executorList) {
        for (DagNodeExecutor executor : executorList) {
            for (NodeType type : executor.supportedTypes()) {
                DagNodeExecutor previous = executors.putIfAbsent(type, executor);
                if (previous != null) {
                    throw new IllegalStateException("DAG 节点类型存在多个执行器: " + type);
                }
            }
        }
    }

    /** 获取指定类型的执行器，缺少绑定时报告配置错误。 */
    public DagNodeExecutor get(NodeType type) {
        DagNodeExecutor executor = executors.get(type);
        if (executor == null) {
            throw new IllegalArgumentException("未注册 DAG 节点执行器: " + type);
        }
        return executor;
    }
}
