package com.melon.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.config.properties.LangChain4jModelProperties;
import com.melon.config.properties.RewriteWorkflowProperties;
import com.melon.workflow.model.ModelRole;
import com.melon.workflow.model.RewriteWorkflowState.DirectionBatch;
import com.melon.workflow.model.RewriteWorkflowState.DirectionEvaluationBatch;
import com.melon.workflow.model.RewriteWorkflowState.ProseAnalysis;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Map;
import java.net.http.HttpTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowModelGatewayTest {

    /** 验证可从 Markdown 围栏中解析结构化模型输出。 */
    @Test
    void parsesJsonFromMarkdownFence() {
        ChatModel defaultModel = mock(ChatModel.class);
        when(defaultModel.chat(any(ChatRequest.class))).thenReturn(response("""
                ```json
                {"surfaceStory":"一个雨夜的故事","unknownField":"ignored"}
                ```
                """));
        WorkflowModelGateway gateway = gateway(defaultModel, Map.of("chatLanguageModel", defaultModel),
                new RewriteWorkflowProperties());

        ProseAnalysis result = gateway.json(ModelRole.ANALYZER, "分析", "原文", ProseAnalysis.class);

        assertThat(result.getSurfaceStory()).containsExactly("一个雨夜的故事");
    }

    /** 验证原文分析模型结果中的数组字段可以解析。 */
    @Test
    void parsesProseAnalysisArrayFields() {
        ChatModel defaultModel = mock(ChatModel.class);
        when(defaultModel.chat(any(ChatRequest.class))).thenReturn(response("""
                {
                  "surfaceStory":["事件一","事件二"],
                  "deepTheme":["主题一","主题二"],
                  "narrativePointOfView":["第一人称回顾"]
                }
                """));
        WorkflowModelGateway gateway = gateway(defaultModel, Map.of("chatLanguageModel", defaultModel),
                new RewriteWorkflowProperties());

        ProseAnalysis result = gateway.json(ModelRole.ANALYZER, "分析", "原文", ProseAnalysis.class);

        assertThat(result.getSurfaceStory()).containsExactly("事件一", "事件二");
        assertThat(result.getDeepTheme()).containsExactly("主题一", "主题二");
        assertThat(result.getNarrativePointOfView()).containsExactly("第一人称回顾");
    }

    /** 验证首次输出无法解析时发起一次 JSON 修复。 */
    @Test
    void retriesOnceWhenModelReturnsInvalidJson() {
        ChatModel defaultModel = mock(ChatModel.class);
        when(defaultModel.chat(any(ChatRequest.class)))
                .thenReturn(response("这不是 JSON"))
                .thenReturn(response("{\"surfaceStory\":\"修复后的结果\"}"));
        WorkflowModelGateway gateway = gateway(defaultModel, Map.of("chatLanguageModel", defaultModel),
                new RewriteWorkflowProperties());

        ProseAnalysis result = gateway.json(ModelRole.ANALYZER, "分析", "原文", ProseAnalysis.class);

        assertThat(result.getSurfaceStory()).containsExactly("修复后的结果");
        var requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(defaultModel, times(2)).chat(requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(request ->
                assertThat(request.responseFormat()).isEqualTo(ResponseFormat.JSON));
        ChatRequest first = requests.getAllValues().getFirst();
        assertThat(first.messages().getFirst()).isInstanceOfSatisfying(SystemMessage.class,
                message -> assertThat(message.text()).contains("ANALYZER", "分析", "JSON"));
        assertThat(first.messages().getLast()).isEqualTo(UserMessage.from("原文"));
        ChatRequest repair = requests.getAllValues().getLast();
        assertThat(repair.messages().getLast()).isInstanceOfSatisfying(UserMessage.class,
                message -> assertThat(message.singleText()).contains("原始输入数据：\n原文",
                        "原始字段及结构要求：\n分析", "这不是 JSON"));
    }

    /** 验证字段类型不匹配时打印完整模型响应、解析异常和字段路径。 */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void logsRawResponseAndFieldPathWhenDirectionTypeMismatches(CapturedOutput output) {
        ChatModel model = mock(ChatModel.class);
        String rawResponse = """
                {"directions":[{"id":"structure-1","researchNeeded":"private research explanation"}]}
                """;
        when(model.chat(any(ChatRequest.class))).thenReturn(response(rawResponse), response("""
                {"directions":[{"id":"structure-1","changes":["调整视角"],
                "researchNeeded":false,"researchQueries":[]}]}
                """));

        DirectionBatch result = gateway(model, Map.of(), new RewriteWorkflowProperties())
                .json(ModelRole.BRAINSTORM_STRUCTURE, RewriteWorkflowPrompts.BRAINSTORM.formatted("结构专家"),
                        "private source text", DirectionBatch.class);

        assertThat(result.getDirections()).singleElement().satisfies(direction -> {
            assertThat(direction.isResearchNeeded()).isFalse();
            assertThat(direction.getChanges()).containsExactly("调整视角");
        });
        verify(model, times(2)).chat(any(ChatRequest.class));
        assertThat(output.getOut()).contains("targetType=DirectionBatch", "errorType=InvalidFormatException",
                        "line=1", "column=", rawResponse, "Cannot deserialize value of type `boolean`",
                        "through reference chain:", "[\"directions\"]", "[0]", "[\"researchNeeded\"]")
                .doesNotContain("private source text");
    }

    /** 验证修复失败也打印对应的完整原始响应和具体解析错误。 */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void logsRawResponseAndParseErrorWhenRepairFails(CapturedOutput output) {
        ChatModel model = mock(ChatModel.class);
        String initialResponse = "首次响应不包含 JSON";
        String repairedResponse = """
                ```json
                {"directions":[{"researchNeeded":"修复后仍不是布尔值"}]}
                ```
                """;
        when(model.chat(any(ChatRequest.class))).thenReturn(response(initialResponse), response(repairedResponse));

        assertThatThrownBy(() -> gateway(model, Map.of(), new RewriteWorkflowProperties())
                .json(ModelRole.BRAINSTORM_STRUCTURE, "方向指令", "原文输入", DirectionBatch.class))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("未返回可解析的 JSON")
                .hasMessageNotContaining(initialResponse)
                .hasMessageNotContaining("修复后仍不是布尔值");

        verify(model, times(2)).chat(any(ChatRequest.class));
        assertThat(output.getAll()).contains(initialResponse, repairedResponse, "没有找到 JSON 对象",
                "InvalidFormatException", "Cannot deserialize value of type `boolean`", "[\"researchNeeded\"]");
    }

    /** 验证两次都缺失 JSON 时保留诊断元数据且不泄漏输入及响应正文。 */
    @Test
    void reportsResponseDetailsWhenRepairAlsoReturnsNoJson() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("private response"))
                .modelName("analysis-model")
                .finishReason(FinishReason.STOP)
                .build());

        assertThatThrownBy(() -> gateway(model, Map.of(), new RewriteWorkflowProperties())
                .json(ModelRole.ANALYZER, "private instruction", "private input", ProseAnalysis.class))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("ANALYZER", "bean=chatLanguageModel", "model=analysis-model",
                        "finishReason=STOP", "outputCharacters=16", "修复")
                .hasMessageNotContaining("private input")
                .hasMessageNotContaining("private instruction")
                .hasMessageNotContaining("private response")
                .hasRootCauseMessage("没有找到 JSON 对象");
        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    /** 验证首次被截断时即使修复正常停止，最终错误仍提示 token 上限。 */
    @Test
    void reportsTruncationFromInitialResponseWhenRepairFails() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("{\"surfaceStory\":[\"未完成"))
                        .finishReason(FinishReason.LENGTH).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("无法修复"))
                        .finishReason(FinishReason.STOP).build());

        assertThatThrownBy(() -> gateway(model, Map.of(), new RewriteWorkflowProperties())
                .json(ModelRole.ANALYZER, "分析", "原文", ProseAnalysis.class))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("LENGTH", "STOP", "max-tokens");
        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    /** 验证修复请求的供应商异常不被误报为 JSON 解析失败或额外重试。 */
    @Test
    void preservesProviderFailureDuringJsonRepair() {
        ChatModel model = mock(ChatModel.class);
        RuntimeException failure = new RuntimeException("provider unavailable");
        when(model.chat(any(ChatRequest.class))).thenReturn(response("这不是 JSON")).thenThrow(failure);

        assertThatThrownBy(() -> gateway(model, Map.of(), new RewriteWorkflowProperties())
                .json(ModelRole.ANALYZER, "分析", "原文", ProseAnalysis.class)).isSameAs(failure);
        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    /** 验证真实网关的 HTTP 200 客户端限制提示在文本和 JSON 节点均直接报错。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsGatewayClientRestrictionWithoutRetry(boolean structured) {
        ChatModel model = mock(ChatModel.class);
        String notice = "本站仅限主流 Coding 客户端/CLI 使用，支持：Codex、Claude Code、OpenCode、Magpie、"
                + "DeepSeek Harness、ZCode、Pi、Oh My Pi。当前客户端不支持。";
        assertThat(notice).hasSize(106);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from(notice)).modelName("gpt-5.5").finishReason(FinishReason.STOP).build());
        var gateway = gateway(model, Map.of(), new RewriteWorkflowProperties());

        assertThatThrownBy(() -> {
            if (structured) {
                gateway.json(ModelRole.ANALYZER, "private instruction", "private input", ProseAnalysis.class);
            } else {
                gateway.text(ModelRole.ANALYZER, "private instruction", "private input");
            }
        }).isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("ANALYZER", "网关拒绝", "当前客户端不支持", "langchain4j.chat.provider")
                .hasMessageNotContaining("private input")
                .hasMessageNotContaining("private instruction");
        verify(model).chat(any(ChatRequest.class));
    }

    /** 验证 JSON 修复阶段的客户端拒绝也直接传播，不再报告为解析失败。 */
    @Test
    void reportsClientRestrictionDuringJsonRepair() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(response("无效 JSON"),
                response("本站仅限主流 Coding 客户端/CLI 使用。当前客户端不支持。"));

        assertThatThrownBy(() -> gateway(model, Map.of(), new RewriteWorkflowProperties())
                .json(ModelRole.ANALYZER, "分析", "原文", ProseAnalysis.class))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("网关拒绝", "当前客户端不支持")
                .hasMessageNotContaining("未返回可解析的 JSON");
        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    /** 验证模型在有效分析中引用同样的文字时，不会误判成网关拒绝。 */
    @Test
    void acceptsJsonQuotingClientRestriction() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(response(
                "{\"surfaceStory\":[\"本站仅限主流 Coding 客户端/CLI 使用。当前客户端不支持。\"]}"));

        ProseAnalysis analysis = gateway(model, Map.of(), new RewriteWorkflowProperties())
                .json(ModelRole.ANALYZER, "分析", "原文", ProseAnalysis.class);

        assertThat(analysis.getSurfaceStory()).containsExactly("本站仅限主流 Coding 客户端/CLI 使用。当前客户端不支持。");
        verify(model).chat(any(ChatRequest.class));
    }

    /** 验证角色配置指向指定的 Spring 模型 Bean。 */
    @Test
    void routesRoleToConfiguredSpringModelBean() {
        ChatModel defaultModel = mock(ChatModel.class);
        ChatModel criticModel = mock(ChatModel.class);
        when(criticModel.chat(any(ChatRequest.class))).thenReturn(response("严格评审结果"));
        RewriteWorkflowProperties properties = new RewriteWorkflowProperties();
        properties.getModelByRole().put(ModelRole.CRITIC, "criticModel");
        WorkflowModelGateway gateway = gateway(defaultModel,
                Map.of("chatLanguageModel", defaultModel, "criticModel", criticModel), properties);

        String result = gateway.text(ModelRole.CRITIC, "评审", "候选稿");

        assertThat(result).isEqualTo("严格评审结果");
        var request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(criticModel).chat(request.capture());
        assertThat(request.getValue().responseFormat()).isNull();
        verifyNoInteractions(defaultModel);
    }

    /** 验证方向吸引力和网络热度评估字段可以解析。 */
    @Test
    void parsesDirectionAppealAndNetworkHeatEvaluation() {
        ChatModel defaultModel = mock(ChatModel.class);
        when(defaultModel.chat(any(ChatRequest.class))).thenReturn(response("""
                {
                  "evaluations":[{
                    "directionId":"style-1",
                    "appealScore":91,
                    "appealReason":"冲突鲜明",
                    "networkHeatScore":76,
                    "networkHeatReason":"实时检索显示相关话题近期讨论活跃"
                  }]
                }
                """));
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

    /** 验证空白模型输出触发重试，随后返回去除首尾空白的文本。 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void retriesEmptyTextAndReturnsTrimmedResult(String emptyText) {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(response(emptyText), response("  恢复后的内容 \n"));

        String result = gateway(model, Map.of(), new RewriteWorkflowProperties())
                .text(ModelRole.BRAINSTORM_STRUCTURE, "构思", "原文");

        assertThat(result).isEqualTo("恢复后的内容");
        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    /** 验证缺失模型响应后重试，并正常解析后续 JSON。 */
    @Test
    void retriesMissingResponseAndJsonParsingContinues() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(null, response("{\"surfaceStory\":[\"恢复后的结果\"]}"));

        ProseAnalysis result = gateway(model, Map.of(), new RewriteWorkflowProperties())
                .json(ModelRole.ANALYZER, "分析", "原文", ProseAnalysis.class);

        assertThat(result.getSurfaceStory()).containsExactly("恢复后的结果");
        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    /** 验证连续空输出的错误包含角色和模型停止原因。 */
    @Test
    void failsWithRoleAndFinishReasonWhenTextRemainsEmpty() {
        ChatModel model = mock(ChatModel.class);
        ChatResponse thinkingOnly = ChatResponse.builder()
                .aiMessage(AiMessage.builder().thinking("内部推理").build())
                .modelName("reasoning-model")
                .finishReason(FinishReason.LENGTH)
                .build();
        when(model.chat(any(ChatRequest.class))).thenReturn(thinkingOnly);

        assertThatThrownBy(() -> gateway(model, Map.of(), new RewriteWorkflowProperties())
                .json(ModelRole.BRAINSTORM_STYLE, "构思", "原文", ProseAnalysis.class))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("BRAINSTORM_STYLE", "连续 2 次", "reasoning-model", "LENGTH")
                .hasMessageNotContaining("内部推理");
        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    /** 验证连续缺失响应时返回清晰的工作流异常。 */
    @Test
    void failsCleanlyWhenResponsesAreMissing() {
        ChatModel model = mock(ChatModel.class);

        assertThatThrownBy(() -> gateway(model, Map.of(), new RewriteWorkflowProperties())
                .text(ModelRole.BRAINSTORM_WORLD, "构思", "原文"))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("BRAINSTORM_WORLD", "response=null");
        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    /** 验证供应商异常直接传递，避免网关叠加重试。 */
    @Test
    void doesNotRetryProviderExceptions() {
        ChatModel model = mock(ChatModel.class);
        RuntimeException failure = new RuntimeException("provider unavailable");
        when(model.chat(any(ChatRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> gateway(model, Map.of(), new RewriteWorkflowProperties())
                .text(ModelRole.ANALYZER, "分析", "原文")).isSameAs(failure);
        verify(model).chat(any(ChatRequest.class));
    }

    /** 验证 HTTP 超时包含角色及生效路由，JSON 网关不会把超时当作格式错误重试。 */
    @Test
    void describesTimeoutAndPreservesOriginalCause() {
        ChatModel model = mock(ChatModel.class);
        var timeout = new dev.langchain4j.exception.TimeoutException(new HttpTimeoutException("request timed out"));
        when(model.chat(any(ChatRequest.class))).thenThrow(timeout);

        assertThatThrownBy(() -> gateway(model, Map.of(), new RewriteWorkflowProperties())
                .json(ModelRole.ANALYZER, "private instruction", "private input", ProseAnalysis.class))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasCause(timeout)
                .hasMessageContaining("ANALYZER", "bean=chatLanguageModel", "耗时", "timeout")
                .hasMessageNotContaining("private instruction")
                .hasMessageNotContaining("private input");
        verify(model).chat(any(ChatRequest.class));
    }

    /** 验证自定义模型 Bean 超时时正确报告 Bean 路由，并保留它的优先级。 */
    @Test
    void identifiesCustomBeanWhenItsHttpRequestTimesOut() {
        ChatModel fallback = mock(ChatModel.class);
        ChatModel selected = mock(ChatModel.class);
        when(selected.chat(any(ChatRequest.class)))
                .thenThrow(new IllegalStateException(new HttpTimeoutException("request timed out")));
        var properties = new RewriteWorkflowProperties();
        properties.getModelByRole().put(ModelRole.CRITIC, "slowModel");
        properties.getProviderByRole().put(ModelRole.CRITIC, "openai");

        assertThatThrownBy(() -> gateway(fallback, Map.of("slowModel", selected), properties)
                .text(ModelRole.CRITIC, "评审", "内容"))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("CRITIC", "bean=slowModel")
                .hasMessageNotContaining("provider=openai");
        verifyNoInteractions(fallback);
        verify(selected).chat(any(ChatRequest.class));
    }

    /** 将预设文本包装成测试模型响应。 */
    private ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.builder().text(text).build()).build();
    }

    /** 用测试模型和角色配置创建模型网关。 */
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
