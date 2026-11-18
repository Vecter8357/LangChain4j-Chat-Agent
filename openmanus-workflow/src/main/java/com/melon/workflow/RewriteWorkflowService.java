package com.melon.workflow;

import com.melon.config.properties.RewriteWorkflowProperties;
import com.melon.workflow.model.DirectionSelectionRequest;
import com.melon.workflow.model.ModelRole;
import com.melon.workflow.model.RewriteOutputStyle;
import com.melon.workflow.model.RewriteWorkflowRequest;
import com.melon.workflow.model.RewriteWorkflowState;
import com.melon.workflow.model.RewriteWorkflowState.CandidateDraft;
import com.melon.workflow.model.RewriteWorkflowState.DirectionBatch;
import com.melon.workflow.model.RewriteWorkflowState.DirectionEvaluation;
import com.melon.workflow.model.RewriteWorkflowState.DirectionEvaluationBatch;
import com.melon.workflow.model.RewriteWorkflowState.DirectionTrendEvidence;
import com.melon.workflow.model.RewriteWorkflowState.DraftReview;
import com.melon.workflow.model.RewriteWorkflowState.FinalSelection;
import com.melon.workflow.model.RewriteWorkflowState.ProseAnalysis;
import com.melon.workflow.model.RewriteWorkflowState.QualityReport;
import com.melon.workflow.model.RewriteWorkflowState.ResearchEvidence;
import com.melon.workflow.model.RewriteWorkflowState.ResearchPlan;
import com.melon.workflow.model.RewriteWorkflowState.ResearchPlanItem;
import com.melon.workflow.model.RewriteWorkflowState.RewriteDirection;
import com.melon.workflow.model.RewriteWorkflowState.WorkflowEvent;
import com.melon.workflow.model.RewriteWorkflowState.WorkflowStatus;
import com.melon.workflow.model.RewriteWorkflowState.StoryboardDraft;
import com.melon.workflow.model.RewriteWorkflowState.StoryboardShot;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.model.image.ImageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentHashMap;

import static com.melon.workflow.RewriteWorkflowPrompts.ANALYZE;
import static com.melon.workflow.RewriteWorkflowPrompts.BRAINSTORM;
import static com.melon.workflow.RewriteWorkflowPrompts.EVALUATE_DIRECTIONS;
import static com.melon.workflow.RewriteWorkflowPrompts.FINAL_EDIT;
import static com.melon.workflow.RewriteWorkflowPrompts.PLAN_RESEARCH;
import static com.melon.workflow.RewriteWorkflowPrompts.QUALITY_REVIEW;
import static com.melon.workflow.RewriteWorkflowPrompts.REVIEW_DRAFT;
import static com.melon.workflow.RewriteWorkflowPrompts.SELECT_FINAL;
import static com.melon.workflow.RewriteWorkflowPrompts.WRITE_DRAFT;
import static com.melon.workflow.RewriteWorkflowPrompts.STORYBOARD_FORMAT;

@Slf4j
@Service
public class RewriteWorkflowService {

    private final WorkflowModelGateway models;
    private final WorkflowImageGateway images;
    private final McpResearchGateway researchGateway;
    private final RewriteWorkflowRepository repository;
    private final RewriteWorkflowProperties properties;
    private final Executor executor;
    private final Set<String> activeWorkflows = ConcurrentHashMap.newKeySet();

    /** 装配角色模型、资料研究、检查点存储和异步执行线程池。 */
    public RewriteWorkflowService(WorkflowModelGateway models,
                                  WorkflowImageGateway images,
                                  McpResearchGateway researchGateway,
                                  RewriteWorkflowRepository repository,
                                  RewriteWorkflowProperties properties,
                                  @Qualifier("rewriteWorkflowExecutor") Executor executor) {
        this.models = models;
        this.images = images;
        this.researchGateway = researchGateway;
        this.repository = repository;
        this.properties = properties;
        this.executor = executor;
    }

    /** 校验改编请求，创建工作流状态并提交分析和方向规划。 */
    public RewriteWorkflowState start(RewriteWorkflowRequest request) {
        return start(request, null);
    }

    public RewriteWorkflowState start(RewriteWorkflowRequest request, String conversationId) {
        validate(request);

        RewriteWorkflowState state = new RewriteWorkflowState();
        state.setWorkflowId(UUID.randomUUID().toString());
        state.setConversationId(conversationId);
        state.setTitle(StringUtils.hasText(request.getTitle()) ? request.getTitle().trim() : "未命名改编");
        state.setSourceText(request.getSourceText().trim());
        state.setTargetAudience(request.getTargetAudience());
        state.setConstraints(request.getConstraints() == null ? new ArrayList<>() : new ArrayList<>(request.getConstraints()));
        state.setResearchEnabled(request.isResearchEnabled());
        state.setOutputStyle(request.getOutputStyle() == null
                ? RewriteOutputStyle.LITERARY_PROSE
                : request.getOutputStyle());
        state.setMaxDirectionSelections(properties.getSelectedDirectionCount());
        state.setCreatedAt(Instant.now());
        state.setUpdatedAt(state.getCreatedAt());
        addEvent(state, "QUEUED", null, "QUEUED", "工作流已进入执行队列");
        repository.save(state);

        try {
            activeWorkflows.add(state.getWorkflowId());
            executor.execute(() -> executePlanning(state));
        } catch (RuntimeException e) {
            activeWorkflows.remove(state.getWorkflowId());
            state.setStatus(WorkflowStatus.FAILED);
            state.setCurrentNode("QUEUE_REJECTED");
            state.setFailureReason("工作流执行队列已满: " + rootMessage(e));
            addEvent(state, "QUEUE", null, "FAILED", state.getFailureReason());
            repository.save(state);
            throw new WorkflowExecutionException(state.getFailureReason(), e);
        }
        return state;
    }

    /** 按工作流 ID 读取持久化状态，空白 ID 直接返回空结果。 */
    public Optional<RewriteWorkflowState> findById(String workflowId) {
        if (!StringUtils.hasText(workflowId)) {
            return Optional.empty();
        }
        return repository.findById(workflowId.trim());
    }

    public Optional<RewriteWorkflowState> findById(String conversationId, String workflowId) {
        return repository.findById(conversationId, workflowId);
    }

    /** 校验选中的方向来自当前候选集合，并提交后续改编流程。 */
    public RewriteWorkflowState selectDirections(String workflowId, DirectionSelectionRequest request) {
        return selectDirections(null, workflowId, request);
    }

    public RewriteWorkflowState selectDirections(String conversationId, String workflowId, DirectionSelectionRequest request) {
        if (!activeWorkflows.add(workflowId)) {
            throw new WorkflowExecutionException("工作流正在执行，请稍后重试");
        }

        boolean submitted = false;
        try {
            RewriteWorkflowState state = (conversationId == null ? repository.findById(workflowId)
                    : repository.findById(conversationId, workflowId))
                .orElseThrow(() -> new WorkflowExecutionException("工作流不存在: " + workflowId));
            applyDirectionSelection(state, request);
            repository.save(state);
            try {
                executor.execute(() -> executeAfterSelection(state));
                submitted = true;
            } catch (RuntimeException error) {
                failWorkflow(state, error);
                throw new WorkflowExecutionException("工作流恢复执行失败", error);
            }
            return state;
        } finally {
            if (!submitted) activeWorkflows.remove(workflowId);
        }
    }

    private void applyDirectionSelection(RewriteWorkflowState state, DirectionSelectionRequest request) {
        if (state.getStatus() != WorkflowStatus.AWAITING_DIRECTION_SELECTION) {
            throw new WorkflowExecutionException("当前工作流不处于等待方向选择状态: " + state.getStatus());
        }
        List<String> requestedIds = request == null || request.getDirectionIds() == null
                ? List.of()
                : request.getDirectionIds().stream().filter(StringUtils::hasText).distinct().toList();
        int maxSelections = Math.max(1, state.getMaxDirectionSelections());
        if (requestedIds.isEmpty() || requestedIds.size() > maxSelections) {
            throw new IllegalArgumentException("请选择 1 到 " + maxSelections + " 个改编方向");
        }

        Map<String, RewriteDirection> available = new LinkedHashMap<>();
        state.getDirections().forEach(direction -> available.put(direction.getId(), direction));
        List<RewriteDirection> selected = requestedIds.stream().map(available::get).toList();
        if (selected.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("选择中包含不存在的改编方向");
        }
        state.setSelectedDirections(new ArrayList<>(selected));
        state.setStatus(WorkflowStatus.RUNNING);
        state.setCurrentNode("USER_DIRECTION_SELECTION");
        addEvent(state, "USER_DIRECTION_SELECTION", "USER", "COMPLETED",
                "用户选择了 " + selected.size() + " 个改编方向");
    }

    /** 分析原文、生成并评估改编方向，然后保存等待用户选择的状态。 */
    private void executePlanning(RewriteWorkflowState state) {
        state.setStatus(WorkflowStatus.RUNNING);
        try {
            checkpoint(state, "ANALYZE", ModelRole.ANALYZER, "STARTED", "开始分析原文");
            analyze(state);
            checkpoint(state, "ANALYZE", ModelRole.ANALYZER, "COMPLETED", "原文文学结构分析完成");

            checkpoint(state, "BRAINSTORM", null, "STARTED", "开始多角色头脑风暴");
            brainstorm(state);
            checkpoint(state, "BRAINSTORM", null, "COMPLETED", "生成 " + state.getDirections().size() + " 个候选方向");

            checkpoint(state, "EVALUATE_DIRECTIONS", ModelRole.CREATIVE_DIRECTOR, "STARTED",
                    "开始评估阅读吸引力与当前网络热度");
            evaluateDirections(state);
            checkpoint(state, "EVALUATE_DIRECTIONS", ModelRole.CREATIVE_DIRECTOR, "COMPLETED",
                    "完成 " + state.getDirections().size() + " 个方向的综合评估");

            state.setMaxDirectionSelections(Math.min(
                    Math.max(1, properties.getSelectedDirectionCount()),
                    state.getDirections().size()));
            state.setStatus(WorkflowStatus.AWAITING_DIRECTION_SELECTION);
            state.setCurrentNode("AWAITING_DIRECTION_SELECTION");
            addEvent(state, "AWAITING_DIRECTION_SELECTION", "USER", "WAITING",
                    "等待用户选择 1 到 " + state.getMaxDirectionSelections() + " 个改编方向");
            repository.save(state);
        } catch (Throwable error) {
            failWorkflow(state, error);
        } finally {
            activeWorkflows.remove(state.getWorkflowId());
        }
    }

    /** 使用选定方向执行研究、写稿、评审和终稿质量检查。 */
    private void executeAfterSelection(RewriteWorkflowState state) {
        try {
            if (state.isResearchEnabled()) {
                checkpoint(state, "RESEARCH", ModelRole.RESEARCH_PLANNER, "STARTED", "开始规划并执行 MCP 网络研究");
                research(state);
                checkpoint(state, "RESEARCH", ModelRole.RESEARCH_PLANNER, "COMPLETED",
                        "完成 " + state.getResearchEvidence().size() + " 项研究任务");
            } else {
                checkpoint(state, "RESEARCH", null, "SKIPPED", "请求已关闭网络研究");
            }

            checkpoint(state, "WRITE_CANDIDATES", ModelRole.DRAFT_WRITER, "STARTED", "开始生成候选改编稿");
            writeCandidates(state);
            checkpoint(state, "WRITE_CANDIDATES", ModelRole.DRAFT_WRITER, "COMPLETED",
                    "生成 " + state.getCandidates().size() + " 篇候选稿");

            checkpoint(state, "REVIEW", ModelRole.CRITIC, "STARTED", "开始对抗性文学评审");
            reviewCandidates(state);
            checkpoint(state, "REVIEW", ModelRole.CRITIC, "COMPLETED",
                    "完成 " + state.getReviews().size() + " 份候选稿评审");

            checkpoint(state, "FINAL_EDIT", ModelRole.FINAL_EDITOR, "STARTED", "开始选择候选稿并生成终稿");
            createFinalDraft(state);
            checkpoint(state, "FINAL_EDIT", ModelRole.FINAL_EDITOR, "COMPLETED", "终稿生成完成");

            qualityLoop(state);
            generateStoryboardImages(state);

            state.setCompletedAt(Instant.now());
            state.setCurrentNode("FINISHED");
            boolean imagesFailed = state.getStoryboardShots().stream()
                    .anyMatch(shot -> "FAILED".equals(shot.getImageStatus()));
            if (state.getQualityReport() != null && state.getQualityReport().isPassed() && !imagesFailed) {
                state.setStatus(WorkflowStatus.COMPLETED);
                addEvent(state, "FINISHED", null, "COMPLETED", "终稿已通过质量门槛");
            } else {
                state.setStatus(WorkflowStatus.NEEDS_REVIEW);
                addEvent(state, "FINISHED", null, "NEEDS_REVIEW", imagesFailed
                        ? "分镜图未全部生成，已保留剧本及成功图片，需要人工审核"
                        : "已达到最大重写轮次，需要人工审核");
            }
            repository.save(state);
        } catch (Throwable error) {
            failWorkflow(state, error);
        } finally {
            activeWorkflows.remove(state.getWorkflowId());
        }
    }

    /** 调用分析角色提取原文结构、文学要素及需要保留的事实。 */
    private void analyze(RewriteWorkflowState state) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("title", state.getTitle());
        input.put("sourceText", state.getSourceText());
        state.setAnalysis(models.json(ModelRole.ANALYZER, ANALYZE, models.toJson(input), ProseAnalysis.class));
    }

    /** 按规划维度生成并汇总可供用户选择的改编方向。 */
    private void brainstorm(RewriteWorkflowState state) {
        List<BrainstormRole> roles = List.of(
                new BrainstormRole(ModelRole.BRAINSTORM_STRUCTURE, "叙事结构创新专家", "structure"),
                new BrainstormRole(ModelRole.BRAINSTORM_STYLE, "文学风格和文体实验专家", "style"),
                new BrainstormRole(ModelRole.BRAINSTORM_WORLD, "世界观、时代与人物关系重构专家", "world")
        );

        Set<String> usedIds = new HashSet<>();
        int sequence = 1;
        for (BrainstormRole role : roles) {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("idPrefix", role.idPrefix());
            input.put("sourceText", state.getSourceText());
            input.put("analysis", state.getAnalysis());
            input.put("targetAudience", state.getTargetAudience());
            input.put("constraints", state.getConstraints());
            addStyleContext(input, state);
            DirectionBatch batch = models.json(role.modelRole(), BRAINSTORM.formatted(role.persona()),
                    models.toJson(input), DirectionBatch.class);
            if (batch.getDirections() == null) {
                continue;
            }
            for (RewriteDirection direction : batch.getDirections()) {
                String candidateId = StringUtils.hasText(direction.getId()) ? direction.getId().trim() : role.idPrefix() + "-" + sequence;
                while (!usedIds.add(candidateId)) {
                    candidateId = role.idPrefix() + "-" + (++sequence);
                }
                direction.setId(candidateId);
                state.getDirections().add(direction);
                sequence++;
            }
        }
        if (state.getDirections().isEmpty()) {
            throw new WorkflowExecutionException("头脑风暴没有生成任何有效改编方向");
        }
    }

    /** 评估各方向的文学表现、读者吸引力及参考资料需求，并按综合得分排序。 */
    private void evaluateDirections(RewriteWorkflowState state) {
        state.getDirectionTrendEvidence().clear();
        for (RewriteDirection direction : state.getDirections()) {
            DirectionTrendEvidence trend = new DirectionTrendEvidence();
            trend.setDirectionId(direction.getId());
            trend.setRetrievedAt(Instant.now());
            String query = LocalDate.now() + " 当前中文网络文学与内容平台中，‘"
                    + direction.getName() + "’、‘" + direction.getCoreIdea()
                    + "’相关主题、体裁和叙事元素的讨论热度与流行趋势";
            trend.setQuery(query);

            if (state.isResearchEnabled()) {
                ResearchPlanItem item = new ResearchPlanItem();
                item.setPurpose("评估改编方向 " + direction.getId() + " 的当前网络热度");
                item.setQuestion(query);
                ResearchEvidence evidence = researchGateway.research(item);
                trend.setSuccessful(evidence.isSuccessful());
                trend.setContent(limit(evidence.getContent(), properties.getMaxResearchResultCharacters()));
                addEvent(state, "DIRECTION_TREND_MCP", "MCP_SEARCH",
                        trend.isSuccessful() ? "COMPLETED" : "UNAVAILABLE",
                        direction.getName() + "：" + (trend.isSuccessful() ? "已取得实时热度证据" : "无实时热度数据"));
            } else {
                trend.setSuccessful(false);
                trend.setContent("用户已关闭网络研究，无法取得当前网络热度数据");
            }
            state.getDirectionTrendEvidence().add(trend);
            repository.save(state);
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("analysis", state.getAnalysis());
        input.put("directions", state.getDirections());
        input.put("trendEvidence", state.getDirectionTrendEvidence());
        input.put("targetAudience", state.getTargetAudience());
        addStyleContext(input, state);
        DirectionEvaluationBatch batch = models.json(ModelRole.CREATIVE_DIRECTOR, EVALUATE_DIRECTIONS,
                models.toJson(input), DirectionEvaluationBatch.class);

        Map<String, DirectionEvaluation> evaluations = new HashMap<>();
        if (batch.getEvaluations() != null) {
            batch.getEvaluations().stream()
                    .filter(evaluation -> StringUtils.hasText(evaluation.getDirectionId()))
                    .forEach(evaluation -> evaluations.put(evaluation.getDirectionId(), evaluation));
        }

        Map<String, DirectionTrendEvidence> trendByDirection = new HashMap<>();
        state.getDirectionTrendEvidence().forEach(item -> trendByDirection.put(item.getDirectionId(), item));
        for (RewriteDirection direction : state.getDirections()) {
            DirectionEvaluation evaluation = evaluations.get(direction.getId());
            int appealScore = evaluation == null ? 50 : clampScore(evaluation.getAppealScore());
            DirectionTrendEvidence evidence = trendByDirection.get(direction.getId());
            boolean heatAvailable = evidence != null && evidence.isSuccessful();
            int heatScore = heatAvailable && evaluation != null ? clampScore(evaluation.getNetworkHeatScore()) : 0;

            direction.setAppealScore(appealScore);
            direction.setAppealReason(evaluation == null ? "评估模型未返回该方向，使用中性分" : evaluation.getAppealReason());
            direction.setNetworkHeatAvailable(heatAvailable);
            direction.setNetworkHeatScore(heatScore);
            direction.setNetworkHeatReason(heatAvailable && evaluation != null
                    ? evaluation.getNetworkHeatReason()
                    : "无实时数据：未配置可用的 MCP 搜索服务或网络研究已关闭");
            int combinedScore = heatAvailable
                    ? (int) Math.round(appealScore * 0.6 + heatScore * 0.4)
                    : appealScore;
            direction.setScore(combinedScore);
            direction.setSelectionReason("阅读吸引力 60% + 当前网络热度 40%；无实时热度时仅按阅读吸引力排序");
        }
        state.getDirections().sort(Comparator.comparingInt(RewriteDirection::getScore).reversed());
    }

    /** 为需要外部资料的方向生成研究计划，限制资料条目与长度并保存取证状态。 */
    private void research(RewriteWorkflowState state) {
        boolean requestedByDirection = state.getSelectedDirections().stream()
                .anyMatch(direction -> direction.isResearchNeeded()
                        || (direction.getResearchQueries() != null && !direction.getResearchQueries().isEmpty()));
        if (!requestedByDirection) {
            addEvent(state, "RESEARCH", null, "SKIPPED", "入选方向不需要外部资料");
            return;
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("maxItems", properties.getMaxResearchItems());
        input.put("mcpSearchEnabled", properties.getResearch().isSearchEnabled());
        input.put("analysis", state.getAnalysis());
        input.put("selectedDirections", state.getSelectedDirections());
        ResearchPlan plan = models.json(ModelRole.RESEARCH_PLANNER, PLAN_RESEARCH,
                models.toJson(input), ResearchPlan.class);
        if (plan.getItems() == null) {
            return;
        }

        plan.getItems().stream().limit(properties.getMaxResearchItems()).forEach(item -> {
            ResearchEvidence evidence = researchGateway.research(item);
            evidence.setContent(limit(evidence.getContent(), properties.getMaxResearchResultCharacters()));
            state.getResearchEvidence().add(evidence);
            addEvent(state, "MCP_FETCH", "MCP_FETCH", evidence.isSuccessful() ? "COMPLETED" : "FAILED",
                    evidence.getUrl() == null ? "未提供 URL" : evidence.getUrl());
            repository.save(state);
        });
    }

    /** 为每个已选方向生成候选稿，并关联方向 ID 及创作时间。 */
    private void writeCandidates(RewriteWorkflowState state) {
        int sequence = 1;
        for (RewriteDirection direction : state.getSelectedDirections()) {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("sourceText", state.getSourceText());
            input.put("analysis", state.getAnalysis());
            input.put("direction", direction);
            input.put("researchEvidence", successfulEvidence(state));
            input.put("targetAudience", state.getTargetAudience());
            input.put("constraints", state.getConstraints());
            addStyleContext(input, state);
            String draft = models.text(ModelRole.DRAFT_WRITER, WRITE_DRAFT, models.toJson(input));
            if (!StringUtils.hasText(draft)) {
                throw new WorkflowExecutionException("方向 " + direction.getId() + " 未生成候选稿");
            }
            CandidateDraft candidate = new CandidateDraft();
            candidate.setId("draft-" + sequence++);
            candidate.setDirectionId(direction.getId());
            candidate.setDirectionName(direction.getName());
            candidate.setContent(draft);
            candidate.setCreatedAt(Instant.now());
            state.getCandidates().add(candidate);
        }
    }

    /** 对每份候选稿执行多维评审并限制评分范围。 */
    private void reviewCandidates(RewriteWorkflowState state) {
        for (CandidateDraft candidate : state.getCandidates()) {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("candidateId", candidate.getId());
            input.put("sourceAnalysis", state.getAnalysis());
            input.put("direction", findDirection(state, candidate.getDirectionId()));
            input.put("candidateDraft", candidate.getContent());
            addStyleContext(input, state);
            DraftReview review = models.json(ModelRole.CRITIC, REVIEW_DRAFT,
                    models.toJson(input), DraftReview.class);
            review.setCandidateId(candidate.getId());
            review.setDisruptionScore(clampScore(review.getDisruptionScore()));
            review.setLiteraryScore(clampScore(review.getLiteraryScore()));
            review.setCoherenceScore(clampScore(review.getCoherenceScore()));
            review.setSourceConnectionScore(clampScore(review.getSourceConnectionScore()));
            review.setGenreComplianceScore(clampScore(review.getGenreComplianceScore()));
            state.getReviews().add(review);
        }
    }

    /** 选择候选稿并提交终稿编辑，选择无法匹配时使用评分最高的候选。 */
    private void createFinalDraft(RewriteWorkflowState state) {
        Map<String, Object> selectionInput = new LinkedHashMap<>();
        selectionInput.put("candidates", state.getCandidates());
        selectionInput.put("reviews", state.getReviews());
        addStyleContext(selectionInput, state);
        FinalSelection selection = models.json(ModelRole.CREATIVE_DIRECTOR, SELECT_FINAL,
                models.toJson(selectionInput), FinalSelection.class);

        CandidateDraft selected = state.getCandidates().stream()
                .filter(candidate -> candidate.getId().equals(selection.getCandidateId()))
                .findFirst()
                .orElseGet(() -> bestCandidate(state));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sourceLiteraryDna", state.getAnalysis().getLiteraryDna());
        input.put("sourceFacts", state.getAnalysis().getFactsToPreserve());
        input.put("selectedCandidate", selected);
        input.put("reviews", state.getReviews());
        input.put("revisionInstructions", selection.getRevisionInstructions());
        input.put("researchEvidence", successfulEvidence(state));
        input.put("targetAudience", state.getTargetAudience());
        input.put("constraints", state.getConstraints());
        addStyleContext(input, state);
        editFinalDraft(state, input);
        if (!StringUtils.hasText(state.getFinalDraft())) {
            throw new WorkflowExecutionException("终稿模型返回为空");
        }
    }

    /** 检查质量门槛并按报告重写终稿，达到通过条件或重写上限后结束。 */
    private void qualityLoop(RewriteWorkflowState state) {
        int round = 0;
        while (true) {
            checkpoint(state, "QUALITY_REVIEW", ModelRole.QUALITY_REVIEWER, "STARTED",
                    "开始第 " + (round + 1) + " 次终稿质量审核");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("qualityThreshold", properties.getQualityThreshold());
            input.put("sourceAnalysis", state.getAnalysis());
            input.put("researchEvidence", successfulEvidence(state));
            input.put("finalDraft", state.getFinalDraft());
            addStyleContext(input, state);
            QualityReport report = models.json(ModelRole.QUALITY_REVIEWER, QUALITY_REVIEW,
                    models.toJson(input), QualityReport.class);
            normalizeQuality(report);
            report.setPassed(report.isPassed()
                    && report.getOverallScore() >= properties.getQualityThreshold()
                    && report.getGenreComplianceScore() >= properties.getQualityThreshold());
            state.setQualityReport(report);
            checkpoint(state, "QUALITY_REVIEW", ModelRole.QUALITY_REVIEWER, "COMPLETED",
                    "质量得分 " + report.getOverallScore() + "，通过=" + report.isPassed());

            if (report.isPassed() || round >= properties.getMaxRewriteRounds()) {
                return;
            }

            round++;
            checkpoint(state, "REVISE_FINAL", ModelRole.FINAL_EDITOR, "STARTED",
                    "根据质量报告进行第 " + round + " 次重写");
            Map<String, Object> revisionInput = new LinkedHashMap<>();
            revisionInput.put("currentFinalDraft", state.getFinalDraft());
            revisionInput.put("qualityReport", report);
            revisionInput.put("sourceLiteraryDna", state.getAnalysis().getLiteraryDna());
            revisionInput.put("constraints", state.getConstraints());
            addStyleContext(revisionInput, state);
            editFinalDraft(state, revisionInput);
            checkpoint(state, "REVISE_FINAL", ModelRole.FINAL_EDITOR, "COMPLETED", "终稿重写完成");
        }
    }

    /** 以同一份结构化分镜组装正文和图片任务，重写时同步替换，避免图文错位。 */
    private void editFinalDraft(RewriteWorkflowState state, Map<String, Object> input) {
        if (state.getOutputStyle() != RewriteOutputStyle.DRAMA_SCRIPT) {
            state.setFinalDraft(models.text(ModelRole.FINAL_EDITOR, FINAL_EDIT, models.toJson(input)));
            return;
        }
        StoryboardDraft draft = models.json(ModelRole.FINAL_EDITOR, FINAL_EDIT + "\n" + STORYBOARD_FORMAT,
                models.toJson(input), StoryboardDraft.class);
        if (draft == null || draft.getShots() == null || draft.getShots().isEmpty()) {
            throw new WorkflowExecutionException("戏剧/剧本终稿未返回分镜头剧本");
        }
        StringBuilder text = new StringBuilder();
        if (StringUtils.hasText(draft.getPreamble())) text.append(draft.getPreamble().trim()).append("\n\n");
        List<StoryboardShot> shots = new ArrayList<>();
        for (StoryboardShot source : draft.getShots()) {
            if (source == null || !StringUtils.hasText(source.getScript())
                    || !StringUtils.hasText(source.getImagePrompt())) {
                throw new WorkflowExecutionException("分镜头缺少剧本文字或分镜图画面描述");
            }
            StoryboardShot shot = new StoryboardShot();
            shot.setNumber(shots.size() + 1);
            shot.setTitle(StringUtils.hasText(source.getTitle()) ? source.getTitle().trim() : "分镜 " + shot.getNumber());
            shot.setScript(source.getScript().trim());
            shot.setImagePrompt(source.getImagePrompt().trim());
            shots.add(shot);
            text.append("### 分镜 ").append(shot.getNumber()).append("：").append(shot.getTitle())
                    .append("\n").append(shot.getScript()).append("\n\n");
        }
        state.setStoryboardShots(shots);
        state.setFinalDraft(text.toString().trim());
    }

    /** 在最后一次质量审核/重写后为每段分镜单独出图；单张失败不丢失其余产物。 */
    private void generateStoryboardImages(RewriteWorkflowState state) {
        if (state.getOutputStyle() != RewriteOutputStyle.DRAMA_SCRIPT) return;
        checkpoint(state, "STORYBOARD_IMAGES", ModelRole.FINAL_EDITOR, "STARTED", "开始逐段生成分镜图");
        Optional<ImageModel> selected;
        try {
            selected = images.model(ModelRole.FINAL_EDITOR);
        } catch (RuntimeException error) {
            state.getStoryboardShots().forEach(shot -> {
                shot.setImageStatus("FAILED");
                shot.setImageError("分镜图模型配置无效，请检查 langchain4j.chat.image 或角色文生图配置");
            });
            checkpoint(state, "STORYBOARD_IMAGES", ModelRole.FINAL_EDITOR, "FAILED", "分镜图模型配置无效，已保留分镜头剧本");
            return;
        }
        if (selected.isEmpty()) {
            state.getStoryboardShots().forEach(shot -> shot.setImageStatus("SKIPPED"));
            checkpoint(state, "STORYBOARD_IMAGES", ModelRole.FINAL_EDITOR, "SKIPPED",
                    "未配置可用生图模型，已保留全部分镜头剧本");
            return;
        }
        int failed = 0;
        for (StoryboardShot shot : state.getStoryboardShots()) {
            try {
                var response = selected.get().generate("生成一张电影分镜图，仅表现一个镜头，不要拼图或添加文字。\n"
                        + shot.getImagePrompt() + "\n分镜头剧本：\n" + shot.getScript());
                shot.setImageUrl(imageUrl(response == null ? null : response.content()));
                shot.setImageStatus("COMPLETED");
            } catch (RuntimeException error) {
                failed++;
                shot.setImageStatus("FAILED");
                shot.setImageError("分镜图生成失败，请检查图像服务状态及模型配置");
                log.warn("工作流 {} 的第 {} 段分镜出图失败，errorType={}",
                        state.getWorkflowId(), shot.getNumber(), error.getClass().getSimpleName());
            }
            checkpoint(state, "STORYBOARD_IMAGES", ModelRole.FINAL_EDITOR, shot.getImageStatus(),
                    "分镜 " + shot.getNumber() + "/" + state.getStoryboardShots().size()
                            + ("COMPLETED".equals(shot.getImageStatus()) ? " 图片生成完成" : " 图片生成失败"));
        }
        checkpoint(state, "STORYBOARD_IMAGES", ModelRole.FINAL_EDITOR, failed == 0 ? "COMPLETED" : "FAILED",
                "分镜图生成结束：成功 " + (state.getStoryboardShots().size() - failed) + " 张，失败 " + failed + " 张");
    }

    private String imageUrl(Image image) {
        if (image == null) throw new WorkflowExecutionException("图像服务返回空图片");
        if (StringUtils.hasText(image.base64Data())) {
            String mime = StringUtils.hasText(image.mimeType()) ? image.mimeType() : "image/png";
            if (!Set.of("image/png", "image/jpeg", "image/webp").contains(mime)) {
                throw new WorkflowExecutionException("图像服务返回不支持的图片格式");
            }
            return "data:" + mime + ";base64," + image.base64Data();
        }
        if (image.url() != null && Set.of("http", "https").contains(image.url().getScheme())) {
            return image.url().toString();
        }
        throw new WorkflowExecutionException("图像服务未返回可用图片");
    }

    /** 按候选稿五项评审分数的平均值选择最高分候选。 */
    private CandidateDraft bestCandidate(RewriteWorkflowState state) {
        Map<String, Integer> scores = new HashMap<>();
        for (DraftReview review : state.getReviews()) {
            int average = (review.getDisruptionScore() + review.getLiteraryScore()
                    + review.getCoherenceScore() + review.getSourceConnectionScore()
                    + review.getGenreComplianceScore()) / 5;
            scores.put(review.getCandidateId(), average);
        }
        return state.getCandidates().stream()
                .max(Comparator.comparingInt(candidate -> scores.getOrDefault(candidate.getId(), 0)))
                .orElseThrow(() -> new WorkflowExecutionException("没有可用于终稿的候选稿"));
    }

    /** 从已选方向中查找对应 ID，查不到时返回空引用。 */
    private RewriteDirection findDirection(RewriteWorkflowState state, String directionId) {
        return state.getSelectedDirections().stream()
                .filter(direction -> direction.getId().equals(directionId))
                .findFirst()
                .orElse(null);
    }

    /** 筛出成功取得的研究资料，供后续写稿与评审使用。 */
    private List<ResearchEvidence> successfulEvidence(RewriteWorkflowState state) {
        return state.getResearchEvidence().stream().filter(ResearchEvidence::isSuccessful).toList();
    }

    /** 将质量报告中的各维度与总评分限制在有效范围内。 */
    private void normalizeQuality(QualityReport report) {
        report.setDisruptionScore(clampScore(report.getDisruptionScore()));
        report.setLiteraryScore(clampScore(report.getLiteraryScore()));
        report.setCoherenceScore(clampScore(report.getCoherenceScore()));
        report.setFactualScore(clampScore(report.getFactualScore()));
        report.setSourceConnectionScore(clampScore(report.getSourceConnectionScore()));
        report.setGenreComplianceScore(clampScore(report.getGenreComplianceScore()));
        report.setOverallScore(clampScore(report.getOverallScore()));
    }

    /** 向模型输入加入文体名称与写作约束，未指定时使用文学散文。 */
    private void addStyleContext(Map<String, Object> input, RewriteWorkflowState state) {
        RewriteOutputStyle style = state.getOutputStyle() == null
                ? RewriteOutputStyle.LITERARY_PROSE
                : state.getOutputStyle();
        input.put("outputStyle", style.name());
        input.put("outputStyleDisplayName", style.displayName());
        input.put("outputStyleInstruction", style.instruction());
        if (style == RewriteOutputStyle.DRAMA_SCRIPT) {
            input.put("storyboardImageExecution", "分镜图由程序在终稿质量审核和重写后按模型实际文生图能力生成。"
                    + "当前文字节点只编写或评审完整分镜头剧本，不生成虚构图片链接，也不因尚未出图扣分。");
        }
    }

    /** 更新当前节点、记录事件并写入改编状态快照。 */
    private void checkpoint(RewriteWorkflowState state, String node, ModelRole role, String eventStatus, String summary) {
        state.setCurrentNode(node);
        addEvent(state, node, role == null ? null : role.name(), eventStatus, summary);
        repository.save(state);
    }

    /** 添加带序号、角色、状态和时间的改编事件。 */
    private void addEvent(RewriteWorkflowState state, String node, String role, String eventStatus, String summary) {
        WorkflowEvent event = new WorkflowEvent();
        event.setSequence(state.getEvents().size() + 1);
        event.setNode(node);
        event.setRole(role);
        event.setStatus(eventStatus);
        event.setSummary(summary);
        event.setOccurredAt(Instant.now());
        state.getEvents().add(event);
        state.setVersion(state.getVersion() + 1);
    }

    /** 检查原文必填、长度上限及方向选择数量配置。 */
    private void validate(RewriteWorkflowRequest request) {
        if (!properties.isEnabled()) {
            throw new WorkflowExecutionException("短文改编工作流已禁用");
        }
        if (request == null || !StringUtils.hasText(request.getSourceText())) {
            throw new IllegalArgumentException("sourceText 不能为空");
        }
        if (request.getSourceText().length() > properties.getMaxSourceCharacters()) {
            throw new IllegalArgumentException("sourceText 超过最大长度 " + properties.getMaxSourceCharacters());
        }
        if (properties.getSelectedDirectionCount() < 1) {
            throw new WorkflowExecutionException("selected-direction-count 必须大于 0");
        }
    }

    /** 将模型评分限制在 0 到 100 之间。 */
    private int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
    }

    /** 按配置截断过长的研究资料并附上截断标记。 */
    private String limit(String content, int maxCharacters) {
        if (content == null || maxCharacters <= 0 || content.length() <= maxCharacters) {
            return content;
        }
        return content.substring(0, maxCharacters) + "\n[研究结果已截断]";
    }

    /** 优先保留带角色等上下文的工作流异常，否则提取底层原因。 */
    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            if (current instanceof WorkflowExecutionException && StringUtils.hasText(current.getMessage())) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    /** 标记工作流失败并记录终止事件，尝试持久化失败快照。 */
    private void failWorkflow(RewriteWorkflowState state, Throwable error) {
        String failureReason = rootMessage(error);
        log.error("改编工作流 {} 执行失败", state.getWorkflowId(), error);
        state.setStatus(WorkflowStatus.FAILED);
        state.setCurrentNode("FAILED");
        state.setFailureReason(failureReason);
        state.setCompletedAt(Instant.now());
        addEvent(state, "FAILED", null, "FAILED", failureReason);
        try {
            repository.save(state);
        } catch (RuntimeException persistenceError) {
            log.error("保存失败工作流 {} 的状态时再次出错", state.getWorkflowId(), persistenceError);
        }
    }

    private record BrainstormRole(ModelRole modelRole, String persona, String idPrefix) {
    }
}
