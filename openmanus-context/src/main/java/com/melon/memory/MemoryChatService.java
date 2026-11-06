package com.melon.memory;

import com.melon.agent.runtime.AgentRun;
import com.melon.knowledge.KnowledgeChatService;
import com.melon.memory.MemoryModels.*;
import dev.langchain4j.data.message.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** 统一聊天上下文：有界历史、早期消息摘录、任务进展和范围内记忆。 */
@Service
@lombok.extern.slf4j.Slf4j
public class MemoryChatService {
    private final MemoryRepository repository;
    private final MemoryService memory;
    private final AgentAnswer agents;
    private final KnowledgeChatService knowledge;
    private final Executor executor;
    private final TaskScheduler scheduler;
    /** 装配会话仓储、记忆和知识问答，以及独立的聊天执行与租约调度。 */
    public MemoryChatService(MemoryRepository repository,MemoryService memory,AgentAnswer agents,
                             KnowledgeChatService knowledge,@Qualifier("memoryChatExecutor") Executor executor,
                             @Qualifier("taskScheduler") TaskScheduler scheduler) {
        this.repository=repository;this.memory=memory;this.agents=agents;this.knowledge=knowledge;this.executor=executor;this.scheduler=scheduler;
    }
    @FunctionalInterface
    public interface AgentAnswer {
        /** 将当前问题、附件、历史和服务端确定的记忆范围传给通用 Agent。 */
        AgentRun run(String objective, List<String> fileIds, List<ChatMessage> history,
                     String memoryContext, String projectId, String taskId);
    }

    /** 在受限线程池中执行，开始后独立续租并保存成功问答。 */
    public CompletableFuture<String> chat(ChatInput input, BooleanSupplier disconnected) {
        MemoryRepository.text(input.input(),12000);
        if(input.conversationId()==null || input.conversationId().isBlank())throw new IllegalArgumentException("请选择会话");
        if(input.fileIds()!=null && input.fileIds().size()>3)throw new IllegalArgumentException("附件最多3个");
        if(input.knowledgeBaseIds()!=null && input.knowledgeBaseIds().size()>20)throw new IllegalArgumentException("知识库最多20个");
        if(input.knowledgeBaseIds()!=null && !input.knowledgeBaseIds().isEmpty() && input.input().length()>2000)throw new IllegalArgumentException("知识库问题最多2000字");
        String evidenceKey=MemoryEvidenceScope.key(input.knowledgeBaseIds(),input.fileIds());
        String id=input.conversationId();String owner=repository.claim(id,evidenceKey);
        try {
            Conversation conversation=repository.conversation(id);
            return CompletableFuture.supplyAsync(()->{
                java.util.concurrent.ScheduledFuture<?> heartbeat=null;
                try {
                    if(disconnected.getAsBoolean() || !repository.heartbeat(id,owner))throw new IllegalStateException("会话排队期间已取消或租约失效");
                    heartbeat=scheduler.scheduleAtFixedRate(()->repository.heartbeat(id,owner),Duration.ofSeconds(20));
                    List<Message> recent=repository.messages(id,Long.MAX_VALUE,100);
                    // 保留最近 12 条的完整内容；更早内容以可追溯摘录压缩，并标记覆盖的消息序号。
                    List<Message> older=recent.subList(0,Math.max(0,recent.size()-12));
                    String summary=conversation.summary();long through=conversation.summaryThrough();
                    StringBuilder excerpts=new StringBuilder(summary);
                    for(Message message:older)if(message.id()>through) {
                        excerpts.append('\n').append(message.role()).append(" #").append(message.id()).append(": ")
                                .append(MemoryRepository.clip(message.content(),240));through=message.id();
                    }
                    if(excerpts.length()>6000)excerpts.delete(0,excerpts.length()-6000);
                    summary=excerpts.toString();repository.summary(id,owner,through,summary);
                    List<ChatMessage> history=new ArrayList<>();int budget=16000;
                    for(Message message:recent.subList(Math.max(0,recent.size()-12),recent.size()).reversed()) {
                        String text=MemoryRepository.clip(message.content(),4000);if(budget<text.length())break;budget-=text.length();
                        history.add("user".equals(message.role())?UserMessage.from(text):AiMessage.from(text));
                    }
                    Collections.reverse(history);
                    if(!history.isEmpty() && history.getFirst() instanceof AiMessage)history.removeFirst();
                    Recall recalled=memory.recall(input.input(),conversation.projectId(),conversation.taskId(),6);
                    StringBuilder context=new StringBuilder("早期消息摘录（可能不完整）：\n").append(summary);
                    if(conversation.taskId()!=null){Task task=repository.task(conversation.taskId());context.append("\n当前任务：").append(task.title()).append("\n状态：").append(task.status()).append("\n进展：").append(task.progress()).append("\n同范围最近运行结果：").append(repository.taskResult(task.id(),evidenceKey));}
                    // 仅注入相关记忆，不把最近写入的任意事实提升为所有任务的规则。
                    LinkedHashMap<String,Entry> entries=new LinkedHashMap<>();
                    recalled.entries().forEach(e->entries.put(e.id(),e));
                    entries.values().forEach(e->context.append("\n[记忆 ").append(e.scope()).append(" / ").append(e.id()).append(" / v").append(e.revision()).append("] ").append(MemoryRepository.clip(e.content(),1200)));
                    String answer;
                    if(input.knowledgeBaseIds()!=null && !input.knowledgeBaseIds().isEmpty()) {
                        answer=knowledge.answer(input.input(),input.knowledgeBaseIds(),input.fileIds(),history,MemoryRepository.clip(context.toString(),14000));
                    } else {
                        AgentRun run=agents.run(input.input(),input.fileIds(),history,MemoryRepository.clip(context.toString(),14000),conversation.projectId(),conversation.taskId());
                        if(run.getStatus()!=AgentRun.Status.COMPLETED)throw new IllegalStateException("Agent 未完成："+run.getFailureReason());
                        answer=run.getFinalAnswer();
                    }
                    if(answer==null || answer.isBlank())throw new IllegalStateException("模型返回空回答");
                    repository.complete(id,owner,input.input(),answer);
                    return answer;
                } catch(RuntimeException error) {
                    log.warn("记忆会话执行失败，conversationId={}，异常类型={}",id,error.getClass().getSimpleName());
                    throw error;
                } finally {
                    if (heartbeat != null) heartbeat.cancel(false);
                    try {
                        repository.release(id, owner);
                    } catch (RuntimeException error) {
                        log.warn("记忆会话释放租约失败，conversationId={}，异常类型={}", id, error.getClass().getSimpleName());
                    }
                }
            },executor);
        } catch(RuntimeException error) {
            repository.release(id,owner);
            throw error;
        }
    }
}
