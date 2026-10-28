package com.melon.workflow.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.workflow.dag.engine.DagWorkflowCompiler;
import com.melon.workflow.dag.model.DagWorkflowDefinition;
import com.melon.workflow.dag.model.DagWorkflowDefinition.Edge;
import com.melon.workflow.dag.model.DagWorkflowDefinition.Node;
import com.melon.workflow.dag.model.DagWorkflowDefinition.NodeType;
import com.melon.workflow.dag.model.DagWorkflowDesign;
import com.melon.workflow.dag.persistence.DagWorkflowDefinitionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DagWorkflowDefinitionServiceTest {

    @Test
    void validatesAndStoresNewDesign() {
        DagWorkflowDefinitionRepository repository = mock(DagWorkflowDefinitionRepository.class);
        when(repository.findByCode("visual-test")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        DagWorkflowDefinitionService service = new DagWorkflowDefinitionService(
                repository, new DagWorkflowCompiler());

        DagWorkflowDesign design = new DagWorkflowDesign();
        design.setDefinition(validDefinition());

        DagWorkflowDesign saved = service.save("visual-test", design);

        assertThat(saved.getDefinition().getCode()).isEqualTo("visual-test");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        verify(repository).save(design);
    }

    private DagWorkflowDefinition validDefinition() {
        ObjectMapper objectMapper = new ObjectMapper();
        DagWorkflowDefinition definition = new DagWorkflowDefinition();
        definition.setCode("visual-test");
        Node start = new Node();
        start.setId("start");
        start.setType(NodeType.START);
        start.setConfig(objectMapper.createObjectNode());
        Node end = new Node();
        end.setId("end");
        end.setType(NodeType.END);
        end.setConfig(objectMapper.createObjectNode());
        Edge edge = new Edge();
        edge.setFrom("start");
        edge.setTo("end");
        definition.setNodes(List.of(start, end));
        definition.setEdges(List.of(edge));
        return definition;
    }
}
