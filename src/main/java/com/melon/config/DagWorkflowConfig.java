package com.melon.config;

import com.melon.config.properties.DagWorkflowProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class DagWorkflowConfig {

    @Bean(name = "dagWorkflowExecutor")
    public Executor dagWorkflowExecutor(DagWorkflowProperties properties) {
        return createExecutor("dag-workflow-", properties.getExecutor());
    }

    @Bean(name = "dagNodeInvocationExecutor")
    public Executor dagNodeInvocationExecutor(DagWorkflowProperties properties) {
        return createExecutor("dag-node-", properties.getNodeExecutor());
    }

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
