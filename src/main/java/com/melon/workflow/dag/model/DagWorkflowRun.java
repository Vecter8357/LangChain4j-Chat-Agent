package com.melon.workflow.dag.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class DagWorkflowRun {

    private String runId;
    private String workflowCode;
    private int workflowVersion;
    private DagWorkflowDefinition definitionSnapshot;
    private JsonNode input;
    private JsonNode output;
    private RunStatus status = RunStatus.QUEUED;
    private Map<String, NodeRun> nodeRuns = new LinkedHashMap<>();
    private List<RunEvent> events = new ArrayList<>();
    private String failureReason;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;

    @Data
    public static class NodeRun {
        private String nodeId;
        private DagWorkflowDefinition.NodeType nodeType;
        private NodeStatus status = NodeStatus.PENDING;
        private int attempt;
        private JsonNode input;
        private JsonNode output;
        private String errorCode;
        private String errorMessage;
        private Instant startedAt;
        private Instant completedAt;
        private long durationMillis;
    }

    @Data
    public static class RunEvent {
        private int sequence;
        private String nodeId;
        private String type;
        private String summary;
        private Instant occurredAt;
    }

    public enum RunStatus {
        QUEUED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public enum NodeStatus {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED,
        SKIPPED,
        CANCELLED
    }
}
