package com.melon.memory;

import com.melon.memory.MemoryModels.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单用户记忆管理接口：所有长期写入由用户明确提交，修订采用乐观锁。 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {
    private final MemoryService service;
    private final MemoryChatService chat;
    /** 注入记忆管理服务和会话聊天服务。 */
    public MemoryController(MemoryService service,MemoryChatService chat) {
        this.service=service;this.chat=chat;
    }
    public record ProjectInput(String name) { }
    public record TaskInput(String projectId,String title) { }
    public record ProgressInput(String status,String progress) { }
    public record ConversationInput(String projectId,String taskId,String title) { }
    public record EntryInput(Scope scope,String scopeId,String content,String source) { }
    public record RevisionInput(int revision,String content) { }
    public record RecallInput(String question,String projectId,String taskId) { }
    /** 列出已保存的项目。 */
    @GetMapping("/projects") public List<Project> projects(){return service.projects();}
    /** 创建用于组织任务和会话的项目。 */
    @PostMapping("/projects") public Project project(@RequestBody ProjectInput input){return service.createProject(input.name());}
    /** 按可选项目 ID 查询任务。 */
    @GetMapping("/tasks") public List<Task> tasks(@RequestParam(required=false) String projectId){return service.tasks(projectId);}
    /** 创建任务，未指定项目时创建独立任务。 */
    @PostMapping("/tasks") public Task task(@RequestBody TaskInput input){return service.createTask(input.projectId(),input.title());}
    /** 更新任务状态和用户维护的进度。 */
    @PatchMapping("/tasks/{id}") public Task progress(@PathVariable String id,@RequestBody ProgressInput input){return service.updateTask(id,input.status(),input.progress());}
    /** 按可选项目 ID 查询已保存的会话。 */
    @GetMapping("/conversations") public List<Conversation> conversations(@RequestParam(required=false) String projectId){return service.conversations(projectId);}
    /** 创建会话，可关联项目和任务。 */
    @PostMapping("/conversations") public Conversation conversation(@RequestBody ConversationInput input){return service.createConversation(input.projectId(),input.taskId(),input.title());}
    /** 按排他的消息 ID 游标分页读取历史消息。 */
    @GetMapping("/conversations/{id}/messages") public List<Message> messages(@PathVariable String id,
            @RequestParam(defaultValue="9223372036854775807") long before,@RequestParam(defaultValue="50") int limit){return service.messages(id,before,limit);}
    /** 删除会话及其消息并返回 204。 */
    @DeleteMapping("/conversations/{id}") public ResponseEntity<Void> deleteConversation(@PathVariable String id){service.deleteConversation(id);return ResponseEntity.noContent().build();}
    /** 列出指定长期、项目或任务范围内的记忆条目。 */
    @GetMapping("/entries") public List<Entry> entries(@RequestParam Scope scope,@RequestParam String scopeId){return service.entries(scope,scopeId);}
    /** 保存用户显式提交的记忆，并在省略来源时填写默认来源。 */
    @PostMapping("/entries") public Entry entry(@RequestBody EntryInput input){return service.createEntry(input.scope(),input.scopeId(),input.content(),input.source()==null?"用户明确记录":input.source());}
    /** 使用请求的修订号更新记忆内容。 */
    @PutMapping("/entries/{id}") public Entry update(@PathVariable String id,@RequestBody RevisionInput input){return service.updateEntry(id,input.revision(),input.content());}
    /** 将记忆条目重新加入向量索引队列。 */
    @PostMapping("/entries/{id}/rebuild") public ResponseEntity<?> rebuild(@PathVariable String id){service.rebuildEntry(id);return ResponseEntity.accepted().body(Map.of("status","PENDING"));}
    /** 标记记忆条目已删除，并返回向量等待清理状态。 */
    @DeleteMapping("/entries/{id}") public ResponseEntity<?> delete(@PathVariable String id){service.deleteEntry(id);return ResponseEntity.accepted().body(Map.of("status","DELETED","vectorCleanup","PENDING"));}
    /** 按问题和项目、任务范围召回相关记忆。 */
    @PostMapping("/recall") public Recall recall(@RequestBody RecallInput input){return service.recall(input.question(),input.projectId(),input.taskId(),8);}
    /** 启动会话问答并通过 SSE 返回答案，跟踪连接断开以便放弃尚未开始的任务。 */
    @PostMapping(value="/chat",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter chat(@RequestBody ChatInput input) {
        SseEmitter emitter = new SseEmitter(600000L);
        AtomicBoolean disconnected = new AtomicBoolean();
        emitter.onCompletion(() -> disconnected.set(true));
        emitter.onTimeout(() -> disconnected.set(true));
        emitter.onError(error -> disconnected.set(true));
        chat.chat(input, disconnected::get).whenComplete((answer, error) -> {
            if (disconnected.get()) return;
            try {
                emitter.send(error == null ? answer : "本次生成失败，未写入会话历史，请重试。");
                emitter.complete();
            } catch (IOException | IllegalStateException sendError) {
                emitter.completeWithError(sendError);
            }
        });
        return emitter;
    }
}
