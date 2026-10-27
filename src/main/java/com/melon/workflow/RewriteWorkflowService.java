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

@Slf4j
@Service
public class RewriteWorkflowService {

    private final WorkflowModelGateway models;
    private final McpResearchGateway researchGateway;
    private final RewriteWorkflowRepository repository;
    private final RewriteWorkflowProperties properties;
    private final Executor executor;
    private final Set<String> activeWorkflows = ConcurrentHashMap.newKeySet();

    public RewriteWorkflowService(WorkflowModelGateway models,
                                  McpResearchGateway researchGateway,
                                  RewriteWorkflowRepository repository,
                                  RewriteWorkflowProperties properties,
                                  @Qualifier("rewriteWorkflowExecutor") Executor executor) {
        this.models = models;
        this.researchGateway = researchGateway;
        this.repository = repository;
        this.properties = properties;
        this.executor = executor;
    }

    public RewriteWorkflowState start(RewriteWorkflowRequest request) {
        validate(request);

        RewriteWorkflowState state = new RewriteWorkflowState();
        state.setWorkflowId(UUID.randomUUID().toString());
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

    public Optional<RewriteWorkflowState> findById(String workflowId) {
        if (!StringUtils.hasText(workflowId)) {
            return Optional.empty();
        }
        return repository.findById(workflowId.trim());
    }

    public RewriteWorkflowState selectDirections(String workflowId, DirectionSelectionRequest request) {
        RewriteWorkflowState state = repository.findById(workflowId)
                .orElseThrow(() -> new WorkflowExecutionException("工作流不存在: " + workflowId));
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
        if (!activeWorkflows.add(workflowId)) {
            throw new WorkflowExecutionException("工作流已经在继续执行，请勿重复提交");
        }

        state.setSelectedDirections(new ArrayList<>(selected));
        state.setStatus(WorkflowStatus.RUNNING);
        state.setCurrentNode("USER_DIRECTION_SELECTION");
        addEvent(state, "USER_DIRECTION_SELECTION", "USER", "COMPLETED",
                "用户选择了 " + selected.size() + " 个改编方向");
        repository.save(state);
        try {
            executor.execute(() -> executeAfterSelection(state));
        } catch (RuntimeException error) {
            activeWorkflows.remove(workflowId);
            failWorkflow(state, error);
            throw new WorkflowExecutionException("工作流恢复执行失败", error);
        }
        return state;
    }

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
            activeWorkflows.remove(state.getWorkflowId());
            repository.save(state);
        } catch (Throwable error) {
            failWorkflow(state, error);
        } finally {
            activeWorkflows.remove(state.getWorkflowId());
        }
    }

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

            state.setCompletedAt(Instant.now());
            state.setCurrentNode("FINISHED");
            if (state.getQualityReport() != null && state.getQualityReport().isPassed()) {
                state.setStatus(WorkflowStatus.COMPLETED);
                addEvent(state, "FINISHED", null, "COMPLETED", "终稿已通过质量门槛");
            } else {
                state.setStatus(WorkflowStatus.NEEDS_REVIEW);
                addEvent(state, "FINISHED", null, "NEEDS_REVIEW", "已达到最大重写轮次，需要人工审核");
            }
            repository.save(state);
        } catch (Throwable error) {
            failWorkflow(state, error);
        } finally {
            activeWorkflows.remove(state.getWorkflowId());
        }
    }

    private void analyze(RewriteWorkflowState state) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("title", state.getTitle());
        input.put("sourceText", state.getSourceText());
        state.setAnalysis(models.json(ModelRole.ANALYZER, ANALYZE, models.toJson(input), ProseAnalysis.class));
    }

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
        state.setFinalDraft(models.text(ModelRole.FINAL_EDITOR, FINAL_EDIT, models.toJson(input)));
        if (!StringUtils.hasText(state.getFinalDraft())) {
            throw new WorkflowExecutionException("终稿模型返回为空");
        }
    }

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
            state.setFinalDraft(models.text(ModelRole.FINAL_EDITOR, FINAL_EDIT, models.toJson(revisionInput)));
            checkpoint(state, "REVISE_FINAL", ModelRole.FINAL_EDITOR, "COMPLETED", "终稿重写完成");
        }
    }

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

    private RewriteDirection findDirection(RewriteWorkflowState state, String directionId) {
        return state.getSelectedDirections().stream()
                .filter(direction -> direction.getId().equals(directionId))
                .findFirst()
                .orElse(null);
    }

    private List<ResearchEvidence> successfulEvidence(RewriteWorkflowState state) {
        return state.getResearchEvidence().stream().filter(ResearchEvidence::isSuccessful).toList();
    }

    private void normalizeQuality(QualityReport report) {
        report.setDisruptionScore(clampScore(report.getDisruptionScore()));
        report.setLiteraryScore(clampScore(report.getLiteraryScore()));
        report.setCoherenceScore(clampScore(report.getCoherenceScore()));
        report.setFactualScore(clampScore(report.getFactualScore()));
        report.setSourceConnectionScore(clampScore(report.getSourceConnectionScore()));
        report.setGenreComplianceScore(clampScore(report.getGenreComplianceScore()));
        report.setOverallScore(clampScore(report.getOverallScore()));
    }

    private void addStyleContext(Map<String, Object> input, RewriteWorkflowState state) {
        RewriteOutputStyle style = state.getOutputStyle() == null
                ? RewriteOutputStyle.LITERARY_PROSE
                : state.getOutputStyle();
        input.put("outputStyle", style.name());
        input.put("outputStyleDisplayName", style.displayName());
        input.put("outputStyleInstruction", style.instruction());
    }

    private void checkpoint(RewriteWorkflowState state, String node, ModelRole role, String eventStatus, String summary) {
        state.setCurrentNode(node);
        addEvent(state, node, role == null ? null : role.name(), eventStatus, summary);
        repository.save(state);
    }

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

    private int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private String limit(String content, int maxCharacters) {
        if (content == null || maxCharacters <= 0 || content.length() <= maxCharacters) {
            return content;
        }
        return content.substring(0, maxCharacters) + "\n[研究结果已截断]";
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

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
