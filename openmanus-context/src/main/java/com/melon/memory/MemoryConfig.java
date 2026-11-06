package com.melon.memory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 复用 Knowledge 已绑定的物理 MySQL 数据源，只初始化记忆专用表。 */
@Configuration
public class MemoryConfig {
    /** 向量调用不占用租约心跳调度线程。 */
    @Bean("memoryIndexScheduler")
    public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler memoryIndexScheduler() {
        var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("memory-index-");return scheduler;
    }
    /** 普通会话与旧知识库问答使用独立有界线程池。 */
    @Bean("memoryChatExecutor")
    public org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor memoryChatExecutor() {
        var executor=new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);executor.setMaxPoolSize(4);executor.setQueueCapacity(20);executor.setThreadNamePrefix("memory-chat-");return executor;
    }
    /** 启用建表配置时在知识库所用的物理数据源初始化记忆表。 */
    @Bean("memorySchema")
    @ConditionalOnProperty(prefix="memory", name="initialize-schema", havingValue="true", matchIfMissing=true)
    public Object memorySchema(@Qualifier("knowledgeJdbc") JdbcTemplate jdbc) {
        new ResourceDatabasePopulator(new ClassPathResource("db/memory-v1.sql")).execute(jdbc.getDataSource());
        return new Object();
    }
}
