package com.melon.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.config.properties.LangChain4jModelProperties;
import com.melon.config.properties.RewriteWorkflowProperties;
import com.melon.workflow.model.ModelRole;
import com.melon.workflow.model.RewriteWorkflowState.DirectionEvaluationBatch;
import com.melon.workflow.model.RewriteWorkflowState.ProseAnalysis;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowModelGatewayTest {

    @Test
    void parsesJsonFromMarkdownFence() {
        ChatModel defaultModel = mock(ChatModel.class);
        when(defaultModel.chat(anyString())).thenReturn("""
                ```json
                {"surfaceStory":"一个雨夜的故事","unknownField":"ignored"}
                ```
                """);
        WorkflowModelGateway gateway = gateway(defaultModel, Map.of("chatLanguageModel", defaultModel),
                new RewriteWorkflowProperties());

        ProseAnalysis result = gateway.json(ModelRole.ANALYZER, "分析", "原文", ProseAnalysis.class);

        assertThat(result.getSurfaceStory()).containsExactly("一个雨夜的故事");
    }

    @Test
    void parsesProseAnalysisArrayFields() {
        ChatModel defaultModel = mock(ChatModel.class);
        when(defaultModel.chat(anyString())).thenReturn("""
                {
                  "surfaceStory":["事件一","事件二"],
                  "deepTheme":["主题一","主题二"],
                  "narrativePointOfView":["第一人称回顾"]
                }
                """);
        WorkflowModelGateway gateway = gateway(defaultModel, Map.of("chatLanguageModel", defaultModel),
                new RewriteWorkflowProperties());

        ProseAnalysis result = gateway.json(ModelRole.ANALYZER, "分析", "原文", ProseAnalysis.class);

        assertThat(result.getSurfaceStory()).containsExactly("事件一", "事件二");
        assertThat(result.getDeepTheme()).containsExactly("主题一", "主题二");
        assertThat(result.getNarrativePointOfView()).containsExactly("第一人称回顾");
    }

    @Test
    void retriesOnceWhenModelReturnsInvalidJson() {
        ChatModel defaultModel = mock(ChatModel.class);
        when(defaultModel.chat(anyString()))
                .thenReturn("这不是 JSON")
                .thenReturn("{\"surfaceStory\":\"修复后的结果\"}");
        WorkflowModelGateway gateway = gateway(defaultModel, Map.of("chatLanguageModel", defaultModel),
                new RewriteWorkflowProperties());

        ProseAnalysis result = gateway.json(ModelRole.ANALYZER, "分析", "原文", ProseAnalysis.class);

        assertThat(result.getSurfaceStory()).containsExactly("修复后的结果");
    }

    @Test
    void routesRoleToConfiguredSpringModelBean() {
        ChatModel defaultModel = mock(ChatModel.class);
        ChatModel criticModel = mock(ChatModel.class);
        when(criticModel.chat(anyString())).thenReturn("严格评审结果");
        RewriteWorkflowProperties properties = new RewriteWorkflowProperties();
        properties.getModelByRole().put(ModelRole.CRITIC, "criticModel");
        WorkflowModelGateway gateway = gateway(defaultModel,
                Map.of("chatLanguageModel", defaultModel, "criticModel", criticModel), properties);

        String result = gateway.text(ModelRole.CRITIC, "评审", "候选稿");

        assertThat(result).isEqualTo("严格评审结果");
        verify(criticModel).chat(anyString());
        verifyNoInteractions(defaultModel);
    }

    @Test
    void parsesDirectionAppealAndNetworkHeatEvaluation() {
        ChatModel defaultModel = mock(ChatModel.class);
        when(defaultModel.chat(anyString())).thenReturn("""
                {
                  "evaluations":[{
                    "directionId":"style-1",
                    "appealScore":91,
                    "appealReason":"冲突鲜明",
                    "networkHeatScore":76,
                    "networkHeatReason":"实时检索显示相关话题近期讨论活跃"
                  }]
                }
                """);
        WorkflowModelGateway gateway = gateway(defaultModel, Map.of("chatLanguageModel", defaultModel),
                new RewriteWorkflowProperties());

        DirectionEvaluationBatch result = gateway.json(ModelRole.CREATIVE_DIRECTOR,
                "评估方向", "候选方向", DirectionEvaluationBatch.class);

        assertThat(result.getEvaluations()).singleElement().satisfies(evaluation -> {
            assertThat(evaluation.getDirectionId()).isEqualTo("style-1");
            assertThat(evaluation.getAppealScore()).isEqualTo(91);
            assertThat(evaluation.getNetworkHeatScore()).isEqualTo(76);
        });
    }

    private WorkflowModelGateway gateway(ChatModel defaultModel,
                                         Map<String, ChatModel> models,
                                         RewriteWorkflowProperties properties) {
        return new WorkflowModelGateway(
                models,
                defaultModel,
                properties,
                new LangChain4jModelProperties(),
                new ObjectMapper()
        );
    }
}
