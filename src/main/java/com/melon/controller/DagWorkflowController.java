package com.melon.controller;

import com.melon.workflow.WorkflowExecutionException;
import com.melon.workflow.dag.DagWorkflowService;
import com.melon.workflow.dag.engine.CompiledDagWorkflow;
import com.melon.workflow.dag.model.DagWorkflowDefinition;
import com.melon.workflow.dag.model.DagWorkflowRun;
import com.melon.workflow.dag.model.DagWorkflowRunRequest;
import com.melon.workflow.dag.model.DagWorkflowStartResponse;
import com.melon.workflow.dag.model.DagWorkflowValidationResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/dag-workflows")
public class DagWorkflowController {

    private final DagWorkflowService workflowService;

    public DagWorkflowController(DagWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping("/validate")
    public DagWorkflowValidationResponse validate(@RequestBody DagWorkflowDefinition definition) {
        CompiledDagWorkflow compiled = workflowService.validate(definition);
        return new DagWorkflowValidationResponse(true, definition.getCode(), definition.getVersion(),
                compiled.topologicalOrder());
    }

    @PostMapping("/runs")
    public ResponseEntity<DagWorkflowStartResponse> start(@RequestBody DagWorkflowRunRequest request) {
        DagWorkflowRun run = workflowService.start(request);
        URI statusUri = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{runId}")
                .buildAndExpand(run.getRunId())
                .toUri();
        return ResponseEntity.accepted().location(statusUri).body(DagWorkflowStartResponse.from(run));
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<DagWorkflowRun> get(@PathVariable String runId) {
        return workflowService.findById(runId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler({IllegalArgumentException.class, WorkflowExecutionException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(RuntimeException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now());
        body.put("error", error.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
