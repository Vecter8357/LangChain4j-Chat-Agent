package com.melon.workflow.dag.executor;

import com.melon.workflow.dag.model.DagWorkflowDefinition.NodeType;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class PassthroughDagNodeExecutor implements DagNodeExecutor {

    @Override
    public Set<NodeType> supportedTypes() {
        return Set.of(NodeType.START, NodeType.CONDITION, NodeType.END);
    }

    @Override
    public DagNodeExecutionResult execute(DagNodeExecutionContext context) {
        return DagNodeExecutionResult.success(context.input());
    }
}
