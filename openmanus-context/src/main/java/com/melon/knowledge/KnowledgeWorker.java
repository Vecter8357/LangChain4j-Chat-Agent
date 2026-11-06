package com.melon.knowledge;

import com.melon.knowledge.KnowledgeModels.Doc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 数据库队列、条件抢占和续租支持进程重启恢复；索引失败可手动重建，删除自动重试五次。 */
@Component
@Slf4j
public class KnowledgeWorker {
    private final KnowledgeRepository repository;
    private final KnowledgeService service;
    private final ThreadPoolTaskExecutor executor;
    private final Map<String,String> running=new ConcurrentHashMap<>();

    /** 装配数据库任务队列、文档处理服务和索引线程池。 */
    public KnowledgeWorker(KnowledgeRepository repository,KnowledgeService service,
                           @Qualifier("knowledgeExecutor") ThreadPoolTaskExecutor executor) {
        this.repository=repository;this.service=service;this.executor=executor;
    }
    /** 扫描可领取的文档任务，在工作池容量内提交执行并收尾已清空的知识库。 */
    @Scheduled(fixedDelayString="${knowledge.poll-millis:2000}",initialDelay=3000)
    public void poll() {
        try {
            for(Doc doc:repository.pending()) {
                if(running.size()>=executor.getMaxPoolSize()) break;
                String owner=UUID.randomUUID().toString();
                if(running.putIfAbsent(doc.id(),owner)!=null) continue;
                try{
                    if(!repository.claim(doc.id(),owner)){running.remove(doc.id(),owner);continue;}
                    executor.execute(()->run(doc.id(),owner));
                }
                catch(RuntimeException rejected){repository.release(doc.id(),owner);running.remove(doc.id(),owner);}
            }
            repository.finishEmptyBases();
        } catch(Exception error){log.error("Knowledge 队列扫描失败",error);}
    }
    /** 定期为本实例仍在执行的文档任务续租。 */
    @Scheduled(fixedDelay=10000,initialDelay=10000)
    public void heartbeat() {
        running.forEach((id,owner)->{try{repository.renew(id,owner);}catch(Exception e){log.warn("Knowledge 续租失败: {}",id);}});
    }
    /** 按文档状态分派删除或索引操作，记录失败并释放租约和本地占位。 */
    private void run(String id,String owner) {
        Doc doc=null;
        try {
            doc=repository.document(id);
            if("DELETING".equals(doc.status())) service.delete(doc,owner);
            else service.build(doc,owner);
        } catch(Exception error) {
            log.warn("Knowledge 文档任务失败: {}",id,error);
            try{repository.failed(id,doc==null?null:doc.pendingIndexId(),owner,error.getMessage()==null?"任务执行失败":error.getMessage());}
            catch(Exception persistError){log.error("Knowledge 任务失败状态保存失败: {}",id,persistError);}
        } finally {
            try{repository.release(id,owner);}finally{running.remove(id,owner);}
        }
    }
}
