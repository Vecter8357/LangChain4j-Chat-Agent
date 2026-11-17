package com.melon.workflow.dag.executor;

import com.melon.workflow.dag.model.DagWorkflowDefinition.NodeType;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class PassthroughDagNodeExecutor implements DagNodeExecutor {

    /** 声明 START、CONDITION 和 END 节点使用透传执行器。 */
    @Override
    public Set<NodeType> supportedTypes() {
        return Set.of(NodeType.START, NodeType.CONDITION, NodeType.END);
    }

    /** 将已解析的节点输入原样作为输出。 */
    @Override
    public DagNodeExecutionResult execute(DagNodeExecutionContext context) {
        return DagNodeExecutionResult.success(context.input());
    }
}
