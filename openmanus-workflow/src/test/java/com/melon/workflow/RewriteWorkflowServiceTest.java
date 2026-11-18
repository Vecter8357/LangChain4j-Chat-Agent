package com.melon.workflow;

import com.melon.config.properties.RewriteWorkflowProperties;
import com.melon.workflow.model.DirectionSelectionRequest;
import com.melon.workflow.model.RewriteWorkflowState;
import com.melon.workflow.model.RewriteWorkflowState.RewriteDirection;
import com.melon.workflow.model.RewriteWorkflowState.WorkflowStatus;
import com.melon.workflow.model.RewriteWorkflowRequest;
import com.melon.workflow.model.RewriteWorkflowState.ProseAnalysis;
import com.melon.workflow.model.ModelRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.net.http.HttpTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class RewriteWorkflowServiceTest {

    /** 验证工作流在等待状态下只接受已存在的改编方向并继续执行。 */
    @Test
    void resumesWorkflowOnlyAfterUserSelectsExistingDirections() {
        WorkflowModelGateway models = mock(WorkflowModelGateway.class);
        McpResearchGateway researchGateway = mock(McpResearchGateway.class);
        RewriteWorkflowRepository repository = mock(RewriteWorkflowRepository.class);
        RewriteWorkflowProperties properties = new RewriteWorkflowProperties();
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        Executor executor = scheduled::set;
        RewriteWorkflowService service = new RewriteWorkflowService(
                models, mock(WorkflowImageGateway.class), researchGateway, repository, properties, executor);

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

    /** 验证保存到工作流状态的超时原因保留角色上下文，页面可以直接展示。 */
    @Test
    void keepsTimeoutContextInFailedWorkflowState() {
        WorkflowModelGateway models = mock(WorkflowModelGateway.class);
        when(models.toJson(any())).thenReturn("{}");
        var timeout = new WorkflowExecutionException("模型角色 ANALYZER 请求超时（bean=chatLanguageModel）",
                new HttpTimeoutException("request timed out"));
        when(models.json(eq(ModelRole.ANALYZER), anyString(), anyString(), eq(ProseAnalysis.class)))
                .thenThrow(timeout);
        var service = new RewriteWorkflowService(models, mock(WorkflowImageGateway.class), mock(McpResearchGateway.class),
                mock(RewriteWorkflowRepository.class), new RewriteWorkflowProperties(), Runnable::run);
        var request = new RewriteWorkflowRequest();
        request.setSourceText("用于验证失败状态的测试原文");

        var state = service.start(request);

        assertThat(state.getStatus()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(state.getFailureReason()).isEqualTo(timeout.getMessage());
    }

    /** 创建带 ID 和名称的测试改编方向。 */
    private RewriteDirection direction(String id, String name) {
        RewriteDirection direction = new RewriteDirection();
        direction.setId(id);
        direction.setName(name);
        return direction;
    }
}
