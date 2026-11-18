package com.melon.memory;

import com.melon.memory.MemoryModels.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

/** MySQL 是消息、任务和记忆的事实源；租约和修订号保护并发写入。 */
@Repository
public class MemoryRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    static final RowMapper<Entry> ENTRY=(r,n)->new Entry(r.getString("id"),Scope.valueOf(r.getString("scope")),
            r.getString("scope_id"),r.getString("content"),r.getString("source"),r.getInt("revision"),
            r.getBoolean("deleted"),r.getString("index_status"),r.getString("embedding_version"),
            r.getString("collection_name"),r.getString("index_error"));
    static final RowMapper<Task> TASK=(r,n)->new Task(r.getString("id"),r.getString("project_id"),
            r.getString("title"),r.getString("status"),r.getString("progress"),r.getString("last_result"));
    static final RowMapper<Conversation> CONVERSATION=(r,n)->new Conversation(r.getString("id"),
            r.getString("project_id"),r.getString("task_id"),r.getString("title"),r.getString("summary"),r.getLong("summary_through"));

    /** 注入固定物理数据源的查询模板和事务模板。 */
    public MemoryRepository(@Qualifier("knowledgeJdbc") JdbcTemplate jdbc,
                            @Qualifier("knowledgeTransaction") TransactionTemplate transaction) {
        this.jdbc=jdbc; this.transaction=transaction;
    }
    /** 创建项目；项目范围由服务端检查，不能仅依赖向量过滤。 */
    public Project createProject(String name) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO la4j_mem_project(id,name) VALUES (?,?)",id,text(name,200));
        return new Project(id,name);
    }
    /** 按创建时间倒序读取最多 200 个项目。 */
    public List<Project> projects() {
        return jdbc.query("SELECT id,name FROM la4j_mem_project ORDER BY created_at DESC LIMIT 200",(r,n)->new Project(r.getString(1),r.getString(2)));
    }
    /** 校验非空项目 ID 已存在，空值表示独立任务或会话。 */
    public void requireProject(String id) {
        if(id!=null && jdbc.queryForObject("SELECT COUNT(*) FROM la4j_mem_project WHERE id=?",Integer.class,id)==0)missing();
    }
    /** 校验项目后创建任务并读取已保存的记录。 */
    public Task createTask(String projectId,String title) {
        requireProject(projectId); String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO la4j_mem_task(id,project_id,title,progress,last_result) VALUES (?,?,?,'','')",id,projectId,text(title,200));
        return task(id);
    }
    /** 按 ID 读取任务，记录不存在时返回 404 异常。 */
    public Task task(String id) {return one(jdbc.query("SELECT * FROM la4j_mem_task WHERE id=?",TASK,id));}
    /** 查询指定项目下的任务，空项目 ID 匹配独立任务。 */
    public List<Task> tasks(String projectId) {
        return jdbc.query("SELECT * FROM la4j_mem_task WHERE project_id <=> ? ORDER BY updated_at DESC LIMIT 200",TASK,projectId);
    }
    /** 校验任务状态和进度文本，保存后返回最新任务记录。 */
    public Task updateTask(String id,String status,String progress) {
        task(id);
        if(!Set.of("OPEN","IN_PROGRESS","COMPLETED","CANCELLED").contains(status))throw new IllegalArgumentException("任务状态无效");
        jdbc.update("UPDATE la4j_mem_task SET status=?,progress=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",status,text(progress,12000),id);
        return task(id);
    }
    /** 校验任务与项目的归属关系后创建空会话。 */
    public Conversation createConversation(String projectId,String taskId,String title) {
        requireProject(projectId);
        if(taskId!=null && !Objects.equals(task(taskId).projectId(),projectId))throw new IllegalArgumentException("任务不属于所选项目");
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO la4j_mem_conversation(id,project_id,task_id,title,summary) VALUES (?,?,?,?,'')",id,projectId,taskId,text(title,200));
        return conversation(id);
    }
    /** 按 ID 读取会话及其摘要游标。 */
    public Conversation conversation(String id) {return one(jdbc.query("SELECT * FROM la4j_mem_conversation WHERE id=?",CONVERSATION,id));}
    /** 查询指定项目下的会话，空项目 ID 匹配独立会话。 */
    public List<Conversation> conversations(String projectId) {
        return jdbc.query("SELECT * FROM la4j_mem_conversation WHERE project_id <=> ? ORDER BY updated_at DESC LIMIT 100",CONVERSATION,projectId);
    }
    /** 按项目和任务查询，确保其他任务的会话不会占用当前任务的历史列表。 */
    public List<Conversation> conversations(String projectId, String taskId) {
        return jdbc.query("""
                SELECT * FROM la4j_mem_conversation WHERE project_id <=> ? AND task_id=?
                ORDER BY updated_at DESC LIMIT 100
                """, CONVERSATION, projectId, taskId);
    }
    /** 同一会话仅允许一个有效执行；过期租约可重试，过期执行不能落盘答案。 */
    public String claim(String id) {
        return claim(id,MemoryEvidenceScope.key(List.of(),List.of()));
    }
    /** 先锁定会话再固定来源；未知来源的旧会话只保留浏览，避免错误继承历史证据。 */
    public String claim(String id,String evidenceKey) {
        return transaction.execute(s->{
        one(jdbc.query("SELECT * FROM la4j_mem_conversation WHERE id=? FOR UPDATE",CONVERSATION,id));
        List<String> scopes=jdbc.query("SELECT evidence_key FROM la4j_mem_conversation_scope WHERE conversation_id=?",(r,n)->r.getString(1),id);
        if(scopes.isEmpty()) {
            if(jdbc.queryForObject("SELECT COUNT(*) FROM la4j_mem_message WHERE conversation_id=?",Integer.class,id)>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"旧会话没有来源范围，请新建会话后继续");
            jdbc.update("INSERT INTO la4j_mem_conversation_scope(conversation_id,evidence_key) VALUES (?,?)",id,evidenceKey);
        } else if(!evidenceKey.equals(scopes.getFirst()))throw new ResponseStatusException(HttpStatus.CONFLICT,"知识库或附件范围已改变，请新建会话");
        String owner=UUID.randomUUID().toString();
        if(jdbc.update("UPDATE la4j_mem_conversation SET lease_owner=?,lease_until=DATE_ADD(NOW(3), INTERVAL 90 SECOND) WHERE id=? AND (lease_until IS NULL OR lease_until<NOW(3))",owner,id)!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"该会话正在生成，请等待完成");
        return owner;
        });
    }
    /** 仅延长由指定持有者持有且尚未过期的会话租约。 */
    public boolean heartbeat(String id,String owner) {
        return jdbc.update("UPDATE la4j_mem_conversation SET lease_until=DATE_ADD(NOW(3), INTERVAL 90 SECOND) WHERE id=? AND lease_owner=? AND lease_until>NOW(3)",id,owner)==1;
    }
    /** 仅在持有者匹配时释放会话租约。 */
    public void release(String id,String owner) {
        jdbc.update("UPDATE la4j_mem_conversation SET lease_owner=NULL,lease_until=NULL WHERE id=? AND lease_owner=?",id,owner);
    }
    /** 只保存完整成功的一问一答，失败不会污染后续历史。 */
    public void complete(String id,String owner,String question,String answer) {
        transaction.executeWithoutResult(s->{
            List<Conversation> locked=jdbc.query("SELECT * FROM la4j_mem_conversation WHERE id=? AND lease_owner=? AND lease_until>NOW(3) FOR UPDATE",CONVERSATION,id,owner);
            if(locked.isEmpty())throw new IllegalStateException("会话执行租约失效");
            jdbc.update("INSERT INTO la4j_mem_message(conversation_id,role,content) VALUES (?,'user',?),(?,'assistant',?)",id,question,id,answer);
            jdbc.update("UPDATE la4j_mem_conversation SET updated_at=NOW(3) WHERE id=?",id);
            if(locked.getFirst().taskId()!=null) {
                String taskId=locked.getFirst().taskId();
                jdbc.update("UPDATE la4j_mem_task SET last_result=?,updated_at=NOW(3) WHERE id=?",clip("最近问题："+question+"\n最近结果："+answer,6000),taskId);
                jdbc.update("INSERT INTO la4j_mem_task_result_scope(task_id,evidence_key) SELECT ?,evidence_key FROM la4j_mem_conversation_scope WHERE conversation_id=? ON DUPLICATE KEY UPDATE evidence_key=VALUES(evidence_key)",taskId,id);
                jdbc.update("INSERT INTO la4j_mem_task_result_origin(task_id,conversation_id) VALUES (?,?) ON DUPLICATE KEY UPDATE conversation_id=VALUES(conversation_id)",taskId,id);
            }
        });
    }
    /** 在排他的消息 ID 游标之前读取一页消息，并按时间顺序返回。 */
    public List<Message> messages(String id,long before,int limit) {
        conversation(id);
        var list=jdbc.query("SELECT * FROM la4j_mem_message WHERE conversation_id=? AND id<? ORDER BY id DESC LIMIT ?",
                (r,n)->new Message(r.getLong("id"),r.getString("role"),r.getString("content"),r.getTimestamp("created_at").toInstant()),
                id,before,Math.max(1,Math.min(100,limit)));
        return list.reversed();
    }
    /** 任务结果只能在相同证据范围复用；人工进展仍是显式任务状态。 */
    public String taskResult(String taskId,String evidenceKey) {
        var matches=jdbc.query("SELECT t.last_result FROM la4j_mem_task t JOIN la4j_mem_task_result_scope s ON s.task_id=t.id JOIN la4j_mem_task_result_origin o ON o.task_id=t.id WHERE t.id=? AND s.evidence_key=?",(r,n)->r.getString(1),taskId,evidenceKey);
        return matches.isEmpty()?"":matches.getFirst();
    }
    /** 在事务中清理关联任务的最近结果，并仅在租约未激活时删除会话。 */
    public void deleteConversation(String id) {
        transaction.executeWithoutResult(s->{
        one(jdbc.query("SELECT * FROM la4j_mem_conversation WHERE id=? FOR UPDATE",CONVERSATION,id));
        jdbc.update("UPDATE la4j_mem_task t JOIN la4j_mem_task_result_origin o ON o.task_id=t.id SET t.last_result='' WHERE o.conversation_id=?",id);
        if(jdbc.update("DELETE FROM la4j_mem_conversation WHERE id=? AND (lease_until IS NULL OR lease_until<NOW(3))",id)!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"会话正在执行，暂不能删除");
        });
    }
    /** 在持有有效租约时保存摘要，并确保已摘要的消息游标只向前推进。 */
    public void summary(String id,String owner,long through,String summary) {
        jdbc.update("UPDATE la4j_mem_conversation SET summary=?,summary_through=? WHERE id=? AND lease_owner=? AND lease_until>NOW(3) AND summary_through<?",summary,through,id,owner,through);
    }
    /** 按 ID 读取记忆条目，包括后台清理使用的已删除记录。 */
    public Entry entry(String id) {return one(jdbc.query("SELECT * FROM la4j_mem_entry WHERE id=?",ENTRY,id));}
    /** 校验指定范围并列出其中尚未删除的记忆条目。 */
    public List<Entry> entries(Scope scope,String scopeId) {
        validateScope(scope,scopeId);
        return jdbc.query("SELECT * FROM la4j_mem_entry WHERE scope=? AND scope_id=? AND deleted=FALSE ORDER BY updated_at DESC LIMIT 500",ENTRY,scope.name(),scopeId);
    }
    /** 校验长期记忆的 local 范围，或确认指定项目、任务存在。 */
    public void validateScope(Scope scope,String scopeId) {
        if(scope==null || scopeId==null)throw new IllegalArgumentException("记忆范围必填");
        switch(scope) {case LONG_TERM->{if(!"local".equals(scopeId))throw new IllegalArgumentException("长期记忆范围为 local");}
            case PROJECT->requireProject(scopeId);case TASK->task(scopeId);}
    }
    /** 校验范围和文本长度，保存条目以等待后台索引。 */
    public Entry createEntry(Scope scope,String scopeId,String content,String source) {
        validateScope(scope,scopeId); String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO la4j_mem_entry(id,scope,scope_id,content,source) VALUES (?,?,?,?,?)",id,scope.name(),scopeId,text(content,4000),text(source,500));
        return entry(id);
    }
    /** 修订后立即使用 MySQL 新内容；向量通过后台任务追平。 */
    public Entry updateEntry(String id,int revision,String content) {
        if(jdbc.update("UPDATE la4j_mem_entry SET content=?,revision=revision+1,index_status='PENDING',retry_at=NULL,index_error=NULL,updated_at=NOW(3) WHERE id=? AND revision=? AND deleted=FALSE",text(content,4000),id,revision)!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"记忆已变更或删除，请刷新");
        return entry(id);
    }
    /** 清空记忆正文、提升修订号，并将向量清理放入待办。 */
    public void deleteEntry(String id) {
        entry(id);
        jdbc.update("UPDATE la4j_mem_entry SET deleted=TRUE,content='',revision=revision+1,index_status='PENDING',retry_at=NULL,updated_at=NOW(3) WHERE id=? AND deleted=FALSE",id);
    }
    /** 模型/Collection 变化后显式重建，保留记忆正文和修订号。 */
    public void rebuildEntry(String id) {
        if(entry(id).deleted())throw new IllegalArgumentException("已删除记忆不能重建");
        jdbc.update("UPDATE la4j_mem_entry SET index_status='PENDING',retry_at=NULL,index_error=NULL WHERE id=? AND deleted=FALSE",id);
    }
    /** 轮询持久化待办；失败定时重试，进程退出后过期租约自动回收。 */
    public List<Entry> pending() {
        return jdbc.query("SELECT * FROM la4j_mem_entry WHERE index_status IN ('PENDING','FAILED','PROCESSING') AND (retry_at IS NULL OR retry_at<NOW(3)) AND (lease_until IS NULL OR lease_until<NOW(3)) ORDER BY updated_at LIMIT 8",ENTRY);
    }
    /** 原子领取记忆索引待办并设置处理租约。 */
    public boolean claimIndex(String id,String owner) {
        return jdbc.update("UPDATE la4j_mem_entry SET lease_owner=?,lease_until=DATE_ADD(NOW(3),INTERVAL 5 MINUTE),index_status='PROCESSING' WHERE id=? AND index_status IN ('PENDING','FAILED','PROCESSING') AND (lease_until IS NULL OR lease_until<NOW(3))",owner,id)==1;
    }
    /** 保存索引版本和 Collection，处理期间修订号变化时重新排队。 */
    public void indexed(Entry entry,String owner,String version,String collection) {
        jdbc.update("UPDATE la4j_mem_entry SET index_status=IF(revision=?,'READY','PENDING'),embedding_version=?,collection_name=?,index_error=NULL,retry_at=NULL,lease_owner=NULL,lease_until=NULL WHERE id=? AND lease_owner=?",
                entry.revision(),version,collection,entry.id(),owner);
    }
    /** 标记当前持有的索引任务失败，安排延迟重试并释放租约。 */
    public void indexFailed(String id,String owner) {
        jdbc.update("UPDATE la4j_mem_entry SET index_status='FAILED',index_error='向量同步失败，将自动重试',retry_at=DATE_ADD(NOW(3),INTERVAL 60 SECOND),lease_owner=NULL,lease_until=NULL WHERE id=? AND lease_owner=?",id,owner);
    }
    /** 校验必填文本及长度上限，然后去除首尾空白。 */
    public static String text(String value,int max) {
        if(value==null || value.isBlank() || value.length()>max)throw new IllegalArgumentException("内容必填，长度不得超过"+max);
        return value.trim();
    }
    /** 按字符数上限截取文本。 */
    public static String clip(String value,int max) {return value.length()>max?value.substring(0,max):value;}
    /** 取查询结果的第一条记录，空结果报告资源不存在。 */
    private static <T>T one(List<T> values) {if(values.isEmpty())missing();return values.getFirst();}
    /** 抛出记忆资源不存在的 404 异常。 */
    private static void missing() {throw new ResponseStatusException(HttpStatus.NOT_FOUND,"记忆资源不存在");}
}
