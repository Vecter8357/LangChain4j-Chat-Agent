package com.melon.knowledge;

import com.melon.knowledge.KnowledgeModels.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.dao.TransientDataAccessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/** MySQL 为文档生命周期和当前可见索引的唯一事实来源。跨存储操作由持久化任务补偿。 */
@Repository
public class KnowledgeRepository {
    final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate tx;

    /** 注入指向同一物理库的查询模板和事务模板，并配置命名参数查询。 */
    public KnowledgeRepository(@Qualifier("knowledgeJdbc") JdbcTemplate jdbc,
                               @Qualifier("knowledgeTransaction") TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.tx = tx;
    }

    /** 将可空的 SQL 时间戳转换为 Instant。 */
    private static Instant instant(ResultSet r, String field) throws SQLException {
        var value = r.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }
    private static final RowMapper<Base> BASE = (r,n) -> new Base(r.getString("id"),r.getString("name"),
            r.getString("description"),r.getString("status"),instant(r,"created_at"));
    private static final RowMapper<Doc> DOC = (r,n) -> new Doc(r.getString("id"),r.getString("base_id"),
            r.getString("name"),"KNOWLEDGE_DOCUMENT",r.getString("status"),r.getInt("document_version"),
            r.getString("active_index_id"),r.getString("pending_index_id"),r.getString("error"),instant(r,"updated_at"));
    private static final RowMapper<Version> VERSION = (r,n) -> new Version(r.getString("document_id"),r.getInt("version"),
            r.getString("name"),r.getString("bucket"),r.getString("object_key"),r.getString("content_type"),
            r.getLong("size"),r.getString("sha256"),instant(r,"created_at"));
    private static final RowMapper<Index> INDEX = (r,n) -> new Index(r.getString("id"),r.getString("document_id"),
            r.getString("base_id"),r.getInt("document_version"),r.getString("status"),r.getString("chunk_version"),
            r.getInt("chunk_size"),r.getInt("chunk_overlap"),r.getString("embedding_model"),r.getString("embedding_version"),
            r.getInt("dimension"),r.getString("collection_name"),r.getString("error"),instant(r,"created_at"));
    private static final RowMapper<Chunk> CHUNK = (r,n) -> new Chunk(r.getString("id"),r.getString("index_id"),
            r.getString("document_id"),r.getString("base_id"),r.getInt("document_version"),r.getInt("ordinal"),r.getString("content"));

    /** 按创建时间倒序读取未删除的知识库。 */
    public List<Base> bases() { return jdbc.query("SELECT * FROM la4j_kb_base WHERE status <> 'DELETED' ORDER BY created_at DESC", BASE); }
    /** 读取未删除的指定知识库，查无记录时返回 404 异常。 */
    public Base base(String id) {
        return jdbc.query("SELECT * FROM la4j_kb_base WHERE id=? AND status <> 'DELETED'", BASE,id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"知识库不存在"));
    }
    /** 分配知识库 ID，保存名称与描述后读取已落库的记录。 */
    public Base createBase(String name, String description) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO la4j_kb_base(id,name,description) VALUES(?,?,?)",id,name,description);
        return base(id);
    }
    /** 按更新时间倒序读取指定知识库中未删除的文档。 */
    public List<Doc> documents(String baseId) {
        return jdbc.query("SELECT * FROM la4j_kb_document WHERE base_id=? AND status <> 'DELETED' ORDER BY updated_at DESC",DOC,baseId);
    }
    /** 读取文档的当前状态和索引指针，供任务执行及上层访问校验使用。 */
    public Doc document(String id) {
        return jdbc.query("SELECT * FROM la4j_kb_document WHERE id=?",DOC,id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"文档不存在"));
    }
    /** 按版本号倒序读取指定文档的原文版本。 */
    public List<Version> versions(String docId) { return jdbc.query("SELECT * FROM la4j_kb_document_version WHERE document_id=? ORDER BY version DESC",VERSION,docId); }
    /** 读取指定原文版本，找不到该版本时返回 404 异常。 */
    public Version version(String docId,int version) { return versions(docId).stream().filter(v->v.version()==version).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"文档版本不存在")); }
    /** 按创建时间倒序读取文档的索引版本。 */
    public List<Index> indexes(String docId) { return jdbc.query("SELECT * FROM la4j_kb_index_version WHERE document_id=? ORDER BY created_at DESC",INDEX,docId); }
    /** 按 ID 读取索引的状态、分块和向量配置。 */
    public Index index(String id) { return jdbc.query("SELECT * FROM la4j_kb_index_version WHERE id=?",INDEX,id).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"索引版本不存在")); }

    /** 先持久化对象位置和任务，随后上传对象；崩溃后仍有可清理的位置记录。 */
    public void reserve(Version version, Index index, boolean newDocument) {
        tx.executeWithoutResult(status -> {
            Base kb=jdbc.queryForObject("SELECT * FROM la4j_kb_base WHERE id=? FOR UPDATE",BASE,index.baseId());
            if(kb==null || !"ACTIVE".equals(kb.status())) throw conflict("知识库正在删除");
            if(newDocument) {
                jdbc.update("INSERT INTO la4j_kb_document(id,base_id,name,status) VALUES(?,?,?,'UPLOADING')",
                        version.documentId(),index.baseId(),version.name());
            } else {
                Doc doc=jdbc.queryForObject("SELECT * FROM la4j_kb_document WHERE id=? FOR UPDATE",DOC,version.documentId());
                requireIdle(doc);
                if(!doc.baseId().equals(index.baseId()) || version.version()!=doc.documentVersion()+1) throw conflict("文档版本已更新，请刷新");
            }
            jdbc.update("INSERT INTO la4j_kb_document_version(document_id,version,name,bucket,object_key,content_type,size,sha256) VALUES(?,?,?,?,?,?,?,?)",
                    version.documentId(),version.version(),version.name(),version.bucket(),version.objectKey(),version.contentType(),version.size(),version.sha256());
            insertIndex(index);
            jdbc.update("UPDATE la4j_kb_document SET name=?,document_version=?,pending_index_id=?,status='UPLOADING',error=NULL,attempts=0,next_attempt=NULL,lease_owner=?,lease_until=DATE_ADD(NOW(3),INTERVAL 180 SECOND),updated_at=NOW(3) WHERE id=?",
                    version.name(),version.version(),index.id(),index.id(),version.documentId());
        });
    }
    /** 插入排队中的索引版本及其分块和向量配置。 */
    private void insertIndex(Index i) {
        jdbc.update("INSERT INTO la4j_kb_index_version(id,document_id,base_id,document_version,status,chunk_version,chunk_size,chunk_overlap,embedding_model,embedding_version,dimension,collection_name) VALUES(?,?,?,?,'QUEUED',?,?,?,?,?,?,?)",
                i.id(),i.documentId(),i.baseId(),i.documentVersion(),i.chunkVersion(),i.chunkSize(),i.chunkOverlap(),i.embeddingModel(),i.embeddingVersion(),i.dimension(),i.collectionName());
    }
    /** 将仍指向该待处理索引的上传中文档推进到索引队列。 */
    public void uploaded(String docId,String indexId) {
        jdbc.update("UPDATE la4j_kb_document SET status='QUEUED',updated_at=NOW(3) WHERE id=? AND pending_index_id=? AND status='UPLOADING'",docId,indexId);
    }
    /** 在事务中记录上传失败，并同步标记关联待处理索引。 */
    public void uploadFailed(String docId,String message) {
        tx.executeWithoutResult(s->{
            int changed=jdbc.update("UPDATE la4j_kb_document SET status='FAILED',error=?,updated_at=NOW(3) WHERE id=? AND status='UPLOADING'",message,docId);
            if(changed==1)jdbc.update("UPDATE la4j_kb_index_version SET status='FAILED',error=? WHERE id=(SELECT pending_index_id FROM la4j_kb_document WHERE id=?)",message,docId);
        });
    }
    /** 锁定空闲文档并校验其原文版本，再写入待处理索引和排队状态。 */
    public void rebuild(Index index) {
        tx.executeWithoutResult(status->{
            Doc doc=jdbc.queryForObject("SELECT * FROM la4j_kb_document WHERE id=? FOR UPDATE",DOC,index.documentId());
            requireIdle(doc);
            if(doc.documentVersion()!=index.documentVersion()) throw conflict("文档版本已变化");
            insertIndex(index);
            jdbc.update("UPDATE la4j_kb_document SET pending_index_id=?,status='QUEUED',error=NULL,attempts=0,updated_at=NOW(3) WHERE id=?",index.id(),doc.id());
        });
    }
    /** 确认文档当前允许上传新版本或重建索引，否则报告状态冲突。 */
    private void requireIdle(Doc d) {
        if(d==null || !Set.of("READY","FAILED").contains(d.status())) throw conflict("文档正在处理或删除，请稍后重试");
    }
    /** 用指定消息构造 409 状态冲突异常。 */
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }

    /** 删除标记提交后检索立即不可见，物理删除交给带租约的后台任务。 */
    public void requestDelete(String docId) {
        jdbc.update("UPDATE la4j_kb_document SET status='DELETING',attempts=0,next_attempt=NULL,error=NULL,updated_at=NOW(3) WHERE id=? AND status <> 'DELETED'",docId);
    }
    /** 在事务中将知识库及其未删除文档标为待删除。 */
    public void requestDeleteBase(String baseId) {
        tx.executeWithoutResult(s->{
            jdbc.queryForObject("SELECT * FROM la4j_kb_base WHERE id=? FOR UPDATE",BASE,baseId);
            jdbc.update("UPDATE la4j_kb_base SET status='DELETING' WHERE id=?",baseId);
            jdbc.update("UPDATE la4j_kb_document SET status='DELETING',attempts=0,next_attempt=NULL,updated_at=NOW(3) WHERE base_id=? AND status <> 'DELETED'",baseId);
        });
    }
    /** 将下属文档均已删除的待删除知识库推进到已删除状态。 */
    public void finishEmptyBases() {
        jdbc.update("UPDATE la4j_kb_base b SET status='DELETED' WHERE b.status='DELETING' AND NOT EXISTS(SELECT 1 FROM la4j_kb_document d WHERE d.base_id=b.id AND d.status <> 'DELETED')");
    }
    /** 处理耗尽重试次数的任务和中断的上传，再查询可领取的文档任务。 */
    public List<Doc> pending() {
        jdbc.update("UPDATE la4j_kb_document SET status=IF(status='DELETING','DELETE_FAILED','FAILED'),error='任务多次中断，请手动重试',updated_at=NOW(3) WHERE status IN ('PROCESSING','DELETING') AND attempts>=5 AND (lease_until IS NULL OR lease_until<NOW(3))");
        jdbc.update("UPDATE la4j_kb_document SET status='FAILED',error='上传中断，请重新上传或删除',updated_at=NOW(3) WHERE status='UPLOADING' AND updated_at < DATE_SUB(NOW(3),INTERVAL 10 MINUTE)");
        return jdbc.query("SELECT * FROM la4j_kb_document WHERE status IN ('QUEUED','PROCESSING','DELETING','DELETE_FAILED') AND (lease_until IS NULL OR lease_until < NOW(3)) AND (next_attempt IS NULL OR next_attempt < NOW(3)) AND attempts < 5 ORDER BY updated_at LIMIT 10",DOC);
    }
    /** 原子领取索引或删除任务，设置租约并递增尝试次数。 */
    public boolean claim(String docId,String owner) {
        return jdbc.update("UPDATE la4j_kb_document SET lease_owner=?,lease_until=DATE_ADD(NOW(3),INTERVAL 60 SECOND),attempts=attempts+1,status=IF(status IN ('DELETING','DELETE_FAILED'),'DELETING','PROCESSING') WHERE id=? AND status IN ('QUEUED','PROCESSING','DELETING','DELETE_FAILED') AND (lease_until IS NULL OR lease_until<NOW(3))",owner,docId)==1;
    }
    /** 为当前持有且尚未过期的文档任务租约续期。 */
    public boolean renew(String docId,String owner) {
        return jdbc.update("UPDATE la4j_kb_document SET lease_until=DATE_ADD(NOW(3),INTERVAL 60 SECOND) WHERE id=? AND lease_owner=? AND lease_until>NOW(3)",docId,owner)==1;
    }
    /** 检查指定持有者是否仍拥有该文档的有效租约。 */
    public boolean owns(String docId,String owner) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)=1 FROM la4j_kb_document WHERE id=? AND lease_owner=? AND lease_until>NOW(3)",Boolean.class,docId,owner));
    }
    /** 在事务内替换索引分块，对瞬时数据库异常最多尝试三次。 */
    public void chunks(String indexId,List<Chunk> chunks) {
        for (int attempt=1; ; attempt++) {
            try {
                tx.executeWithoutResult(s->{
            jdbc.update("DELETE FROM la4j_kb_chunk WHERE index_id=?",indexId);
            jdbc.batchUpdate("INSERT INTO la4j_kb_chunk(id,index_id,document_id,base_id,document_version,ordinal,content) VALUES(?,?,?,?,?,?,?)",
                    chunks,100,(ps,c)->{ ps.setString(1,c.id());ps.setString(2,c.indexId());ps.setString(3,c.documentId());
                        ps.setString(4,c.baseId());ps.setInt(5,c.documentVersion());ps.setInt(6,c.ordinal());ps.setString(7,c.content()); });
                });
                return;
            } catch (TransientDataAccessException error) {
                // InnoDB FULLTEXT 维护可能出现瞬时死锁；整批事务回滚后重试，不重做外部对象写入。
                if (attempt >= 3) throw error;
                try { Thread.sleep(50L * attempt); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("分块写入重试被中断", interrupted);
                }
            }
        }
    }
    /** 在租约和待处理索引仍匹配时，原子切换可见索引并更新版本状态。 */
    public boolean activate(String docId,String indexId,String owner) {
        return Boolean.TRUE.equals(tx.execute(s->{
            int changed=jdbc.update("UPDATE la4j_kb_document SET active_index_id=?,pending_index_id=NULL,status='READY',error=NULL,updated_at=NOW(3) WHERE id=? AND pending_index_id=? AND status='PROCESSING' AND lease_owner=? AND lease_until>NOW(3)",indexId,docId,indexId,owner);
            if(changed==1) jdbc.update("UPDATE la4j_kb_index_version SET status='READY',error=NULL WHERE id=?",indexId);
            return changed==1;
        }));
    }
    /** 保存文档处理错误及下次重试时间，并标记尚未生效的索引版本失败。 */
    public void failed(String docId,String indexId,String owner,String error) {
        tx.executeWithoutResult(s->{
            jdbc.update("UPDATE la4j_kb_document SET status=IF(status='DELETING','DELETE_FAILED','FAILED'),error=?,next_attempt=DATE_ADD(NOW(3),INTERVAL 30 SECOND),updated_at=NOW(3) WHERE id=? AND lease_owner=?",error,docId,owner);
            if(indexId!=null) jdbc.update("UPDATE la4j_kb_index_version SET status='FAILED',error=? WHERE id=? AND status <> 'READY'",error,indexId);
        });
    }
    /** 仅在持有者匹配时清除文档任务租约。 */
    public void release(String docId,String owner) { jdbc.update("UPDATE la4j_kb_document SET lease_owner=NULL,lease_until=NULL WHERE id=? AND lease_owner=?",docId,owner); }
    /** 校验删除租约后清理分块并更新文档与索引的删除状态。 */
    public void deleted(String docId,String owner) {
        tx.executeWithoutResult(s->{
            if(!owns(docId,owner)) throw conflict("删除任务租约已失效");
            jdbc.update("DELETE FROM la4j_kb_chunk WHERE document_id=?",docId);
            jdbc.update("UPDATE la4j_kb_index_version SET status='DELETED' WHERE document_id=?",docId);
            jdbc.update("UPDATE la4j_kb_document SET status='DELETED',active_index_id=NULL,pending_index_id=NULL,error=NULL,updated_at=NOW(3) WHERE id=? AND status='DELETING' AND lease_owner=?",docId,owner);
        });
    }
    /** 仅返回仍可见的当前索引；旧版本保留记录，但不会参与检索。 */
    public List<Index> activeIndexes(List<String> bases,List<String> files) {
        String sql="SELECT i.* FROM la4j_kb_index_version i JOIN la4j_kb_document d ON d.active_index_id=i.id JOIN la4j_kb_base b ON b.id=d.base_id WHERE b.status='ACTIVE' AND d.status NOT IN ('DELETING','DELETE_FAILED','DELETED') AND i.status='READY' AND b.id IN (:bases)";
        Map<String,Object> p=new HashMap<>();p.put("bases",bases);
        if(files!=null && !files.isEmpty()){sql+=" AND d.id IN (:files)";p.put("files",files);}
        return named.query(sql,p,INDEX);
    }
    /** 按分块序号读取指定索引的前 500 个分块供预览使用。 */
    public List<Chunk> chunkList(String indexId) { return jdbc.query("SELECT * FROM la4j_kb_chunk WHERE index_id=? ORDER BY ordinal LIMIT 500",CHUNK,indexId); }
    /** 在指定索引范围内按 MySQL 全文相关性查询有限数量的分块。 */
    public List<Chunk> lexical(String question,List<String> indexes,int limit) {
        return named.query("SELECT * FROM la4j_kb_chunk WHERE index_id IN (:ids) AND MATCH(content) AGAINST (:q IN NATURAL LANGUAGE MODE)>0 ORDER BY MATCH(content) AGAINST (:q IN NATURAL LANGUAGE MODE) DESC, id LIMIT :n",
                Map.of("ids",indexes,"q",question,"n",limit),CHUNK);
    }
    /** 批量读取候选分块，空 ID 集合直接返回空结果。 */
    public List<Chunk> byIds(Collection<String> ids) {
        return ids.isEmpty()?List.of():named.query("SELECT * FROM la4j_kb_chunk WHERE id IN (:ids)",Map.of("ids",ids),CHUNK);
    }
}
