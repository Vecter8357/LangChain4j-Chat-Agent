package com.melon.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "dag-workflow")
public class DagWorkflowProperties {
    private boolean enabled = true;
    private String runKeyPrefix = "dag:workflow:run:";
    private String runIndexKey = "dag:workflow:runs";
    private Duration runTtl = Duration.ofDays(30);
    private String definitionKeyPrefix = "dag:workflow:definition:";
    private String definitionIndexKey = "dag:workflow:definitions";
    private Executor executor = new Executor();
    private Executor nodeExecutor = new Executor(4, 8, 50);

    @Data
    public static class Executor {
        private int corePoolSize = 2;
        private int maxPoolSize = 4;
        private int queueCapacity = 20;

        /** 创建线程池配置，供 Spring 通过属性绑定填充。 */
        public Executor() {
        }

        /** 创建具有指定核心线程数、最大线程数和队列容量的配置。 */
        public Executor(int corePoolSize, int maxPoolSize, int queueCapacity) {
            this.corePoolSize = corePoolSize;
            this.maxPoolSize = maxPoolSize;
            this.queueCapacity = queueCapacity;
        }
    }
}
