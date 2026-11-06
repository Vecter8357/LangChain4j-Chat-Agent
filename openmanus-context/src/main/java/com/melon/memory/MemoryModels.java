package com.melon.memory;

import java.time.Instant;
import java.util.List;

/** 记忆系统的数据契约；当前单用户部署的长期记忆使用固定 local 范围。 */
public final class MemoryModels {
    /** 保留用于组织记忆数据类型的静态命名空间。 */
    private MemoryModels() { }
    public enum Scope { LONG_TERM, PROJECT, TASK }
    public record Project(String id, String name) { }
    public record Task(String id, String projectId, String title, String status, String progress, String lastResult) { }
    public record Conversation(String id, String projectId, String taskId, String title,
                               String summary, long summaryThrough) { }
    public record Message(long id, String role, String content, Instant createdAt) { }
    public record Entry(String id, Scope scope, String scopeId, String content, String source,
                        int revision, boolean deleted, String indexStatus, String embeddingVersion,
                        String collectionName, String indexError) { }
    public record Recall(List<Entry> entries, List<String> warnings) { }
    public record ChatInput(String conversationId, String input, List<String> fileIds,
                            List<String> knowledgeBaseIds) { }
}
