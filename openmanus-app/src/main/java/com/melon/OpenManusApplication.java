package com.melon;

import com.melon.agent.runtime.PlatformAgentService;
import com.melon.memory.MemoryChatService;
import com.melon.workflow.dag.executor.GeneralAgentRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class OpenManusApplication {

    /** 启动 Spring Boot 应用并传递命令行参数。 */
    public static void main(String[] args) {
        SpringApplication.run(OpenManusApplication.class, args);
    }

    /** 将会话服务的 Agent 回调绑定到通用 Agent 入口。 */
    @Bean
    MemoryChatService.AgentAnswer memoryAgentAnswer(PlatformAgentService agents) {
        return agents::runGeneral;
    }

    /** 将 DAG 的通用 Agent 引用绑定到聊天使用的同一门面。 */
    @Bean
    GeneralAgentRunner generalAgentRunner(PlatformAgentService agents) {
        return agents::runGeneralForWorkflow;
    }
}
