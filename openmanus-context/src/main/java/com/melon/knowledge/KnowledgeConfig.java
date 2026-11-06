package com.melon.knowledge;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;

/** 只在明确配置的库初始化 la4j_kb_ 前缀表，隔离业务库路由与后台线程。 */
@Configuration
@EnableScheduling
public class KnowledgeConfig {
    /** 保留业务库的动态路由模板，避免知识库专用模板抑制 Boot 的默认 JDBC 自动配置。 */
    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /** 从动态数据源中选定知识库使用的物理连接，按配置建表并设置查询超时。 */
    @Bean("knowledgeJdbc")
    public JdbcTemplate knowledgeJdbc(DataSource dataSource, KnowledgeProperties properties) {
        if (!(dataSource instanceof DynamicRoutingDataSource routing)) {
            throw new IllegalStateException("Knowledge 需要动态数据源配置");
        }
        DataSource selected = routing.getDataSources().get(properties.getDatasource());
        if (selected == null) throw new IllegalArgumentException("Knowledge 指定的数据源不存在");
        if (properties.isInitializeSchema()) {
            new ResourceDatabasePopulator(new ClassPathResource("db/knowledge-v1.sql")).execute(selected);
        }
        JdbcTemplate jdbc = new JdbcTemplate(selected);
        jdbc.setQueryTimeout(15);
        return jdbc;
    }

    /** 为知识库创建读已提交的事务模板，配合显式行锁提交状态切换。 */
    @Bean("knowledgeTransaction")
    public TransactionTemplate knowledgeTransaction(@Qualifier("knowledgeJdbc") JdbcTemplate jdbc) {
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        // 显式行锁保护版本切换；READ_COMMITTED 减少并发空索引清理的 gap-lock 竞争。
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    /** 创建容量受限的文档索引和删除线程池。 */
    @Bean("knowledgeExecutor")
    public ThreadPoolTaskExecutor knowledgeExecutor(KnowledgeProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getWorkers());
        executor.setMaxPoolSize(properties.getWorkers());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("knowledge-index-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }

    /** 提供知识库任务轮询和会话、文档租约续期使用的共享调度器。 */
    @Bean("taskScheduler")
    public ThreadPoolTaskScheduler knowledgeScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("knowledge-jobs-");
        return scheduler;
    }

    /** 创建处理独立知识库问答的有界线程池。 */
    @Bean("knowledgeChatExecutor")
    public ThreadPoolTaskExecutor knowledgeChatExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("knowledge-chat-");
        return executor;
    }
}
