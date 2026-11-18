package com.melon.memory;

import com.melon.memory.MemoryModels.*;
import com.melon.config.properties.LangChain4jModelProperties;
import com.melon.config.properties.RagProperties;
import com.melon.knowledge.KnowledgeProperties;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/** 分范围召回和异步向量同步；向量只存 ID/修订号，提示词内容始终回查 MySQL。 */
@Service
public class MemoryService {
    private final MemoryRepository repository;
    private final EmbeddingModel embedding;
    private final EmbeddingStore<TextSegment> store;
    private final String version;
    private final String collection;
    private final RagProperties.QdrantProperties qdrant;
    /** 装配记忆仓储、向量存储与用于判断索引兼容性的模型配置。 */
    public MemoryService(MemoryRepository repository,EmbeddingModel embedding,EmbeddingStore<TextSegment> store,
                         LangChain4jModelProperties models,KnowledgeProperties knowledge,RagProperties rag) {
        this.repository=repository;this.embedding=embedding;this.store=store;
        this.collection=rag.getVectorStore().getQdrant().getCollectionName();
        this.qdrant=rag.getVectorStore().getQdrant();
        try {var model=models.getEmbedding();version=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                (model.resolvedProvider()+"|"+model.resolvedBaseUrl()+"|"+model.resolvedModelName()+"|"+knowledge.getEmbeddingRevision()).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    /** 每个条目带独占租约；重复同步使用确定性 Point ID，支持崩溃后幂等重试。 */
    @Scheduled(fixedDelayString="${memory.index-poll-ms:5000}",initialDelay=5000,scheduler="memoryIndexScheduler")
    public void synchronize() {
        for(Entry candidate:repository.pending()) synchronize(candidate.id());
    }
    /** 单条同步便于失败重试和隔离的真实服务验证。 */
    void synchronize(String id) {
            String owner=UUID.randomUUID().toString();
            if(!repository.claimIndex(id,owner))return;
            Entry entry=repository.entry(id);
            try {
                if(entry.collectionName()!=null && !entry.collectionName().equals(collection))cleanPreviousCollection(entry);
                // 同一 Collection 中与知识库、临时附件隔离，删除仅作用于该记忆。
                store.removeAll(metadataKey("scope").isEqualTo("memory").and(metadataKey("memory_id").isEqualTo(entry.id())));
                if(!entry.deleted()) {
                    var vector=embedding.embed(entry.content()).content();
                    Metadata metadata=new Metadata().put("scope","memory").put("owner","local")
                            .put("memory_id",entry.id()).put("memory_scope",entry.scope().name())
                            .put("scope_id",entry.scopeId()).put("revision",entry.revision()).put("embedding_version",version);
                    String point=UUID.nameUUIDFromBytes((entry.id()+":"+entry.revision()+":"+version).getBytes(StandardCharsets.UTF_8)).toString();
                    store.addAll(List.of(point),List.of(vector),List.of(TextSegment.from("memory:"+entry.id(),metadata)));
                }
                repository.indexed(entry,owner,version,collection);
            } catch(Exception error){repository.indexFailed(entry.id(),owner);}
    }
    /** 切换 Collection 后清理旧位置；失败保留待办，避免静默遗留已删除记忆。 */
    private void cleanPreviousCollection(Entry entry) throws Exception {
        var builder=io.qdrant.client.QdrantGrpcClient.newBuilder(qdrant.getHost(),qdrant.getPort(),qdrant.isUseTls()).withTimeout(qdrant.getTimeout());
        if(qdrant.getApiKey()!=null && !qdrant.getApiKey().isBlank())builder.withApiKey(qdrant.getApiKey());
        try(var client=new io.qdrant.client.QdrantClient(builder.build())) {
            if(client.collectionExistsAsync(entry.collectionName(),qdrant.getTimeout()).get()) {
                var old=dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore.builder().client(client).collectionName(entry.collectionName()).payloadTextKey(qdrant.getPayloadTextKey()).build();
                old.removeAll(metadataKey("scope").isEqualTo("memory").and(metadataKey("memory_id").isEqualTo(entry.id())));
            }
        }
    }
    /** 列出已保存的项目。 */
    public List<Project> projects() { return repository.projects(); }

    /** 创建项目以组织任务、会话和项目记忆。 */
    public Project createProject(String name) { return repository.createProject(name); }

    /** 校验可选项目 ID 后列出该范围内的任务。 */
    public List<Task> tasks(String projectId) {
        repository.requireProject(projectId);
        return repository.tasks(projectId);
    }

    /** 在所选项目中创建任务，或在空项目范围创建独立任务。 */
    public Task createTask(String projectId, String title) { return repository.createTask(projectId, title); }

    /** 保存任务状态和手动维护的进度。 */
    public Task updateTask(String id, String status, String progress) {
        return repository.updateTask(id, status, progress);
    }

    /** 校验可选项目 ID 后查询对应范围的会话。 */
    public List<Conversation> conversations(String projectId) {
        return conversations(projectId, null);
    }

    public List<Conversation> conversations(String projectId, String taskId) {
        repository.requireProject(projectId);
        return taskId == null ? repository.conversations(projectId) : repository.conversations(projectId, taskId);
    }

    /** 创建会话，可绑定所选项目及其任务。 */
    public Conversation createConversation(String projectId, String taskId, String title) {
        return repository.createConversation(projectId, taskId, title);
    }

    /** 通过排他的消息 ID 游标读取一页历史。 */
    public List<Message> messages(String id, long before, int limit) {
        return repository.messages(id, before, limit);
    }

    /** 删除指定会话及其历史，执行中的会话由仓储租约保护。 */
    public void deleteConversation(String id) { repository.deleteConversation(id); }

    /** 列出指定长期、项目或任务范围的有效条目。 */
    public List<Entry> entries(Scope scope, String scopeId) { return repository.entries(scope, scopeId); }

    /** 保存显式记忆及其来源，等待后台向量同步。 */
    public Entry createEntry(Scope scope, String scopeId, String content, String source) {
        return repository.createEntry(scope, scopeId, content, source);
    }

    /** 按预期修订号更新内容并触发向量同步。 */
    public Entry updateEntry(String id, int revision, String content) {
        return repository.updateEntry(id, revision, content);
    }

    /** 将现有记忆重新加入向量同步队列。 */
    public void rebuildEntry(String id) { repository.rebuildEntry(id); }

    /** 逻辑删除记忆并安排向量清理。 */
    public void deleteEntry(String id) { repository.deleteEntry(id); }

    /**
     * 召回 local 长期记忆及当前授权的项目和任务记忆。
     * 以 MySQL 中的条目为准，检查 Qdrant 索引版本并在降级时返回提示。
     */
    public Recall recall(String question,String projectId,String taskId,int limit) {
        MemoryRepository.text(question,12000); repository.requireProject(projectId);
        if(taskId!=null && !Objects.equals(repository.task(taskId).projectId(),projectId))throw new IllegalArgumentException("任务范围不匹配");
        List<Entry> allowed=new ArrayList<>(repository.entries(Scope.LONG_TERM,"local"));
        if(projectId!=null)allowed.addAll(repository.entries(Scope.PROJECT,projectId));
        if(taskId!=null)allowed.addAll(repository.entries(Scope.TASK,taskId));
        if(allowed.isEmpty())return new Recall(List.of(),List.of());
        Map<String,Entry> byId=new LinkedHashMap<>();allowed.forEach(e->byId.put(e.id(),e));
        Map<String,Double> scores=new HashMap<>();List<String> warnings=new ArrayList<>();
        Set<String> terms=terms(question);
        allowed.forEach(e->{Set<String> content=terms(e.content());long matched=terms.stream().filter(content::contains).count();
            if(matched>0)scores.put(e.id(),(double)matched/Math.max(1,terms.size()));});
        try {
            var filter=metadataKey("scope").isEqualTo("memory").and(metadataKey("owner").isEqualTo("local"))
                    .and(metadataKey("memory_id").isIn(new ArrayList<>(byId.keySet())))
                    .and(metadataKey("embedding_version").isEqualTo(version));
            var query=embedding.embed(question).content();
            for(var match:store.search(EmbeddingSearchRequest.builder().queryEmbedding(query).filter(filter).maxResults(24).minScore(0.55).build()).matches()) {
                var metadata=match.embedded().metadata();Entry e=byId.get(metadata.getString("memory_id"));
                if(e!=null && e.revision()==metadata.getInteger("revision"))scores.merge(e.id(),match.score(),Double::sum);
            }
        } catch(Exception error){warnings.add("向量记忆召回暂不可用，已使用 MySQL 词项匹配");}
        if(allowed.stream().anyMatch(e->!version.equals(e.embeddingVersion()) || !collection.equals(e.collectionName())))
            warnings.add("部分记忆尚未同步当前向量版本，仍可通过 MySQL 召回；可重建索引");
        List<Entry> result=scores.keySet().stream().sorted(Comparator.<String>comparingDouble(scores::get).reversed().thenComparing(id->id))
                .map(repository::entry).filter(e->!e.deleted() && e.revision()==byId.get(e.id()).revision())
                .limit(Math.max(1,Math.min(limit,12))).toList();
        return new Recall(result,warnings);
    }
    /** 有限长度的词项匹配兜底，中文使用双字片段；不冒充向量语义检索。 */
    static Set<String> terms(String input) {
        Set<String> terms=new HashSet<>();
        for(String token:input.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if(token.isBlank())continue;
            if(token.codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN)) {
                if(token.length()==1)terms.add(token);
                for(int i=0;i<token.length()-1;i++)terms.add(token.substring(i,i+2));
            } else terms.add(token);
        }
        return terms;
    }
}
