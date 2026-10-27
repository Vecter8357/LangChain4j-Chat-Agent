package com.melon.workflow;

import com.melon.config.properties.RewriteWorkflowProperties;
import com.melon.workflow.model.DirectionSelectionRequest;
import com.melon.workflow.model.RewriteWorkflowState;
import com.melon.workflow.model.RewriteWorkflowState.RewriteDirection;
import com.melon.workflow.model.RewriteWorkflowState.WorkflowStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RewriteWorkflowServiceTest {

    @Test
    void resumesWorkflowOnlyAfterUserSelectsExistingDirections() {
        WorkflowModelGateway models = mock(WorkflowModelGateway.class);
        McpResearchGateway researchGateway = mock(McpResearchGateway.class);
        RewriteWorkflowRepository repository = mock(RewriteWorkflowRepository.class);
        RewriteWorkflowProperties properties = new RewriteWorkflowProperties();
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        Executor executor = scheduled::set;
        RewriteWorkflowService service = new RewriteWorkflowService(
                models, researchGateway, repository, properties, executor);

        RewriteDirection first = direction("structure-1", "倒叙真相");
        RewriteDirection second = direction("style-1", "多声部叙事");
        RewriteWorkflowState state = new RewriteWorkflowState();
        state.setWorkflowId("workflow-1");
        state.setStatus(WorkflowStatus.AWAITING_DIRECTION_SELECTION);
        state.setMaxDirectionSelections(2);
        state.setDirections(List.of(first, second));
        when(repository.findById("workflow-1")).thenReturn(Optional.of(state));

        DirectionSelectionRequest request = new DirectionSelectionRequest();
        request.setDirectionIds(List.of("style-1"));
        RewriteWorkflowState resumed = service.selectDirections("workflow-1", request);

        assertThat(resumed.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(resumed.getCurrentNode()).isEqualTo("USER_DIRECTION_SELECTION");
        assertThat(resumed.getSelectedDirections()).extracting(RewriteDirection::getId)
                .containsExactly("style-1");
        assertThat(scheduled.get()).isNotNull();
        verify(repository).save(state);
    }

    private RewriteDirection direction(String id, String name) {
        RewriteDirection direction = new RewriteDirection();
        direction.setId(id);
        direction.setName(name);
        return direction;
    }
}
