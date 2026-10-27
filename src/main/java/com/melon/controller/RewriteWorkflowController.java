package com.melon.controller;

import com.melon.workflow.RewriteWorkflowService;
import com.melon.workflow.WorkflowExecutionException;
import com.melon.workflow.model.DirectionSelectionRequest;
import com.melon.workflow.model.RewriteWorkflowRequest;
import com.melon.workflow.model.RewriteWorkflowStartResponse;
import com.melon.workflow.model.RewriteWorkflowState;
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
@RequestMapping("/api/rewrite-workflows")
public class RewriteWorkflowController {

    private final RewriteWorkflowService workflowService;

    public RewriteWorkflowController(RewriteWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping
    public ResponseEntity<RewriteWorkflowStartResponse> start(@RequestBody RewriteWorkflowRequest request) {
        RewriteWorkflowState state = workflowService.start(request);
        RewriteWorkflowStartResponse response = RewriteWorkflowStartResponse.from(state);
        URI statusUri = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{workflowId}")
                .buildAndExpand(state.getWorkflowId())
                .toUri();
        return ResponseEntity.accepted().location(statusUri).body(response);
    }

    @GetMapping("/{workflowId}")
    public ResponseEntity<RewriteWorkflowState> get(@PathVariable String workflowId) {
        return workflowService.findById(workflowId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{workflowId}/directions")
    public ResponseEntity<RewriteWorkflowStartResponse> selectDirections(
            @PathVariable String workflowId,
            @RequestBody DirectionSelectionRequest request) {
        RewriteWorkflowState state = workflowService.selectDirections(workflowId, request);
        URI statusUri = ServletUriComponentsBuilder.fromCurrentRequest()
                .replacePath("/api/rewrite-workflows/{workflowId}")
                .buildAndExpand(workflowId)
                .toUri();
        return ResponseEntity.accepted()
                .location(statusUri)
                .body(RewriteWorkflowStartResponse.from(state));
    }

    @ExceptionHandler({IllegalArgumentException.class, WorkflowExecutionException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(RuntimeException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now());
        body.put("error", error.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
