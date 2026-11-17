package com.melon.config;

import com.melon.config.properties.DagWorkflowProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class DagWorkflowConfig {

    /** 创建调度整次 DAG 运行的线程池。 */
    @Bean(name = "dagWorkflowExecutor")
    public Executor dagWorkflowExecutor(DagWorkflowProperties properties) {
        return createExecutor("dag-workflow-", properties.getExecutor());
    }

    /** 创建执行单个节点调用的独立线程池，供运行线程等待超时结果。 */
    @Bean(name = "dagNodeInvocationExecutor")
    public Executor dagNodeInvocationExecutor(DagWorkflowProperties properties) {
        return createExecutor("dag-node-", properties.getNodeExecutor());
    }

    /** 根据配置设置线程名、并发上限和排队容量。 */
    private Executor createExecutor(String prefix, DagWorkflowProperties.Executor config) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(config.getCorePoolSize());
        executor.setMaxPoolSize(config.getMaxPoolSize());
        executor.setQueueCapacity(config.getQueueCapacity());
        executor.setThreadNamePrefix(prefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
