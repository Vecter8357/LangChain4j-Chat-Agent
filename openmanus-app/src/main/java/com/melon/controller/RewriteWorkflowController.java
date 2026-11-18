package com.melon.controller;

import com.melon.memory.ConversationRewriteWorkflowStore;
import com.melon.memory.ConversationRewriteWorkflowStore.RunSummary;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class RewriteWorkflowController {

    private static final String WORKFLOWS = "/api/rewrite-workflows";
    private static final String CONVERSATION_WORKFLOWS = "/api/memory/conversations/{conversationId}/rewrite-workflows";
    private final RewriteWorkflowService workflowService;
    private final ConversationRewriteWorkflowStore history;

    /** 注入负责改编流程启动、查询和续跑的工作流服务。 */
    public RewriteWorkflowController(RewriteWorkflowService workflowService, ConversationRewriteWorkflowStore history) {
        this.workflowService = workflowService;
        this.history = history;
    }

    /** 提交改编任务并返回 202，Location 指向状态查询地址。 */
    @PostMapping({WORKFLOWS, CONVERSATION_WORKFLOWS})
    public ResponseEntity<RewriteWorkflowStartResponse> start(
            @PathVariable(required = false) String conversationId, @RequestBody RewriteWorkflowRequest request) {
        RewriteWorkflowState state = conversationId == null ? workflowService.start(request)
                : workflowService.start(request, conversationId);
        RewriteWorkflowStartResponse response = RewriteWorkflowStartResponse.from(state);
        URI statusUri = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{workflowId}")
                .buildAndExpand(state.getWorkflowId())
                .toUri();
        return ResponseEntity.accepted().location(statusUri).body(response);
    }

    /** 按工作流 ID 读取当前状态，查不到时返回 404。 */
    @GetMapping({WORKFLOWS + "/{workflowId}", CONVERSATION_WORKFLOWS + "/{workflowId}"})
    public ResponseEntity<RewriteWorkflowState> get(@PathVariable(required = false) String conversationId,
                                                     @PathVariable String workflowId) {
        return (conversationId == null ? workflowService.findById(workflowId)
                : workflowService.findById(conversationId, workflowId))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(CONVERSATION_WORKFLOWS)
    public List<RunSummary> list(@PathVariable String conversationId) {
        return history.list(conversationId);
    }

    /** 接收用户选择的方向，提交后续改编并返回状态查询地址。 */
    @PostMapping({WORKFLOWS + "/{workflowId}/directions", CONVERSATION_WORKFLOWS + "/{workflowId}/directions"})
    public ResponseEntity<RewriteWorkflowStartResponse> selectDirections(
            @PathVariable(required = false) String conversationId,
            @PathVariable String workflowId,
            @RequestBody DirectionSelectionRequest request) {
        RewriteWorkflowState state = conversationId == null ? workflowService.selectDirections(workflowId, request)
                : workflowService.selectDirections(conversationId, workflowId, request);
        URI statusUri = ServletUriComponentsBuilder.fromCurrentServletMapping()
                .path((conversationId == null ? WORKFLOWS : CONVERSATION_WORKFLOWS) + "/{workflowId}")
                .buildAndExpand(conversationId == null ? new Object[]{workflowId} : new Object[]{conversationId, workflowId})
                .toUri();
        return ResponseEntity.accepted()
                .location(statusUri)
                .body(RewriteWorkflowStartResponse.from(state));
    }

    /** 将参数和工作流执行异常转换为包含时间与错误消息的 400 响应。 */
    @ExceptionHandler({IllegalArgumentException.class, WorkflowExecutionException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(RuntimeException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now());
        body.put("error", error.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
