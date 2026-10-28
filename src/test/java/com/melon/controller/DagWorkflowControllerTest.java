package com.melon.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.agent.capability.CapabilityRegistry;
import com.melon.workflow.dag.DagWorkflowDefinitionService;
import com.melon.workflow.dag.DagWorkflowService;
import com.melon.workflow.dag.model.DagWorkflowDefinition;
import com.melon.workflow.dag.model.DagWorkflowValidationResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DagWorkflowControllerTest {

    @Test
    void convertsCompilerMessageToNodeValidationIssue() {
        DagWorkflowService workflowService = mock(DagWorkflowService.class);
        DagWorkflowDefinition definition = new DagWorkflowDefinition();
        definition.setCode("invalid-visual-test");
        when(workflowService.validate(definition)).thenThrow(new IllegalArgumentException(
                "DAG 工作流定义无效: LLM 节点 analyze 必须配置 config.role 和 config.instruction"));
        DagWorkflowController controller = new DagWorkflowController(
                workflowService,
                mock(DagWorkflowDefinitionService.class),
                mock(CapabilityRegistry.class),
                new ObjectMapper());

        DagWorkflowValidationResponse response = controller.validate(definition);

        assertThat(response.valid()).isFalse();
        assertThat(response.errors()).singleElement().satisfies(issue -> {
            assertThat(issue.scope()).isEqualTo("NODE");
            assertThat(issue.targetId()).isEqualTo("analyze");
            assertThat(issue.field()).isEqualTo("config.role");
            assertThat(issue.code()).isEqualTo("REQUIRED");
        });
    }
}
