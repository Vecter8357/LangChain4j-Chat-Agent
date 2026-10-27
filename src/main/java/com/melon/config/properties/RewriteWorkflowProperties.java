package com.melon.config.properties;

import com.melon.workflow.model.ModelRole;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "rewrite-workflow")
public class RewriteWorkflowProperties {

    private boolean enabled = true;

    private int maxSourceCharacters = 20_000;

    private int selectedDirectionCount = 2;

    private int maxResearchItems = 4;

    private int maxResearchResultCharacters = 8_000;

    private int maxRewriteRounds = 2;

    private int qualityThreshold = 80;

    private Persistence persistence = new Persistence();

    private Executor executor = new Executor();

    private Research research = new Research();

    /**
     * 模型角色到 Spring Bean 名称的映射。未配置的角色使用主 ChatModel。
     */
    private Map<ModelRole, String> modelByRole = new EnumMap<>(ModelRole.class);

    /**
     * 角色到已配置 provider（ollama/openai/zhipu/aihub）的映射。
     * modelByRole 优先级更高，可用于选择用户自定义的 ChatModel Bean。
     */
    private Map<ModelRole, String> providerByRole = new EnumMap<>(ModelRole.class);

    @Data
    public static class Persistence {

        private String keyPrefix = "rewrite:workflow:";

        private Duration ttl = Duration.ofDays(30);

        private boolean required = true;
    }

    @Data
    public static class Executor {

        private int corePoolSize = 2;

        private int maxPoolSize = 4;

        private int queueCapacity = 20;
    }

    @Data
    public static class Research {

        /** 是否优先调用额外配置的 MCP 搜索服务。 */
        private boolean searchEnabled = false;

        /** 例如 npx -y your-mcp-search-server。 */
        private List<String> searchCommand = new ArrayList<>();

        /** 传递给 MCP 子进程的环境变量，密钥应通过外部环境注入。 */
        private Map<String, String> environment = new HashMap<>();

        private Duration timeout = Duration.ofSeconds(120);

        private boolean debug = false;
    }
}
