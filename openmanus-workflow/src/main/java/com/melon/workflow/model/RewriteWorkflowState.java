package com.melon.workflow.model;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
public class RewriteWorkflowState {

    private String workflowId;
    private String title;
    private String sourceText;
    private String targetAudience;
    private List<String> constraints = new ArrayList<>();
    private boolean researchEnabled;
    private RewriteOutputStyle outputStyle = RewriteOutputStyle.LITERARY_PROSE;
    private int maxDirectionSelections = 1;

    private WorkflowStatus status = WorkflowStatus.QUEUED;
    private String currentNode = "QUEUED";
    private int version;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;
    private String failureReason;
    private boolean persisted;
    private String persistenceError;

    private ProseAnalysis analysis;
    private List<RewriteDirection> directions = new ArrayList<>();
    private List<RewriteDirection> selectedDirections = new ArrayList<>();
    private List<DirectionTrendEvidence> directionTrendEvidence = new ArrayList<>();
    private List<ResearchEvidence> researchEvidence = new ArrayList<>();
    private List<CandidateDraft> candidates = new ArrayList<>();
    private List<DraftReview> reviews = new ArrayList<>();
    private String finalDraft;
    private QualityReport qualityReport;
    private List<WorkflowEvent> events = new ArrayList<>();

    public enum WorkflowStatus {
        QUEUED,
        RUNNING,
        AWAITING_DIRECTION_SELECTION,
        COMPLETED,
        NEEDS_REVIEW,
        FAILED
    }

    @Data
    public static class ProseAnalysis {
        private List<String> sourceGenre = new ArrayList<>();
        private List<String> surfaceStory = new ArrayList<>();
        private List<String> deepTheme = new ArrayList<>();
        private List<String> narrativePointOfView = new ArrayList<>();
        private List<String> emotionalArc = new ArrayList<>();
        private List<String> keyImages = new ArrayList<>();
        private List<String> literaryDna = new ArrayList<>();
        private List<String> subvertibleAssumptions = new ArrayList<>();
        private List<String> factsToPreserve = new ArrayList<>();
    }

    @Data
    public static class DirectionBatch {
        private List<RewriteDirection> directions = new ArrayList<>();
    }

    @Data
    public static class RewriteDirection {
        private String id;
        private String name;
        private String coreIdea;
        private List<String> changes = new ArrayList<>();
        private boolean researchNeeded;
        private List<String> researchQueries = new ArrayList<>();
        private String risk;
        private int appealScore;
        private String appealReason;
        private boolean networkHeatAvailable;
        private int networkHeatScore;
        private String networkHeatReason;
        private int score;
        private String selectionReason;
    }

    @Data
    public static class DirectionEvaluationBatch {
        private List<DirectionEvaluation> evaluations = new ArrayList<>();
    }

    @Data
    public static class DirectionEvaluation {
        private String directionId;
        private int appealScore;
        private String appealReason;
        private int networkHeatScore;
        private String networkHeatReason;
    }

    @Data
    public static class DirectionTrendEvidence {
        private String directionId;
        private String query;
        private String content;
        private boolean successful;
        private Instant retrievedAt;
    }

    @Data
    public static class DirectionSelection {
        private List<SelectedDirection> selections = new ArrayList<>();
    }

    @Data
    public static class SelectedDirection {
        private String directionId;
        private int score;
        private String reason;
    }

    @Data
    public static class ResearchPlan {
        private List<ResearchPlanItem> items = new ArrayList<>();
    }

    @Data
    public static class ResearchPlanItem {
        private String purpose;
        private String question;
        private String url;
    }

    @Data
    public static class ResearchEvidence {
        private String purpose;
        private String question;
        private String url;
        private String content;
        private boolean successful;
        private Instant retrievedAt;
    }

    @Data
    public static class CandidateDraft {
        private String id;
        private String directionId;
        private String directionName;
        private String content;
        private Instant createdAt;
    }

    @Data
    public static class DraftReview {
        private String candidateId;
        private int disruptionScore;
        private int literaryScore;
        private int coherenceScore;
        private int sourceConnectionScore;
        private int genreComplianceScore;
        private List<String> strengths = new ArrayList<>();
        private List<String> problems = new ArrayList<>();
        private List<String> revisionInstructions = new ArrayList<>();
        private String verdict;
    }

    @Data
    public static class FinalSelection {
        private String candidateId;
        private List<String> revisionInstructions = new ArrayList<>();
        private String reason;
    }

    @Data
    public static class QualityReport {
        private int disruptionScore;
        private int literaryScore;
        private int coherenceScore;
        private int factualScore;
        private int sourceConnectionScore;
        private int genreComplianceScore;
        private int overallScore;
        private boolean passed;
        private List<String> issues = new ArrayList<>();
        private List<String> requiredChanges = new ArrayList<>();
    }

    @Data
    public static class WorkflowEvent {
        private int sequence;
        private String node;
        private String role;
        private String status;
        private String summary;
        private Instant occurredAt;
    }
}
