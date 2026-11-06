package com.melon.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.melon.knowledge.KnowledgeCapabilityRegistrar;
import com.melon.agent.capability.CapabilityDescriptor;
import com.melon.agent.capability.CapabilityProvisioningService;
import com.melon.agent.capability.CapabilityRegistry;
import com.melon.config.properties.AgentRuntimeProperties;
import com.melon.workflow.dag.executor.GeneralAgentRunner;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 现有业务与 Agent Runtime 之间的统一门面。
 * 负责准备能力集合、构造 Agent 定义并将运行结果转换为业务可消费的答案。
 */
@Service
public class PlatformAgentService {

    private static final String GENERAL_SYSTEM_PROMPT = """
            你是统一智能助手。根据用户目标自主判断是否需要使用工具。
            GitHub 能力用于查询代码仓库；Fetch 能力用于读取明确网页；文件系统能力只能访问允许目录；
            Knowledge 能力只能查询当前请求提供的文件。引用外部资料时必须区分事实和推断。
            """;

    private final AgentRuntime agentRuntime;
    private final CapabilityRegistry capabilityRegistry;
    private final CapabilityProvisioningService provisioningService;
    private final AgentRuntimeProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 创建平台 Agent 门面。
     */
    public PlatformAgentService(AgentRuntime agentRuntime,
                                CapabilityRegistry capabilityRegistry,
                                CapabilityProvisioningService provisioningService,
                                AgentRuntimeProperties properties,
                                ObjectMapper objectMapper) {
        this.agentRuntime = agentRuntime;
        this.capabilityRegistry = capabilityRegistry;
        this.provisioningService = provisioningService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 运行通用聊天 Agent，并按需提供 MCP、本地文件系统和文档知识能力。
     *
     * @param objective 用户目标
     * @param fileIds 当前请求允许查询的文件 ID
     * @return Agent 运行结果
     */
    public AgentRun runGeneral(String objective, List<String> fileIds) {
        return runGeneral(objective, fileIds, List.of(), null);
    }

    /** 会话入口提供经过服务端范围校验和长度限制的历史与记忆。 */
    public AgentRun runGeneral(String objective, List<String> fileIds,
                               List<dev.langchain4j.data.message.ChatMessage> history, String memoryContext) {
        return runGeneral(objective,fileIds,history,memoryContext,null,null);
    }

    /** 固定当前会话的记忆范围，避免二次工具召回退回全局范围。 */
    public AgentRun runGeneral(String objective,List<String> fileIds,
                               List<dev.langchain4j.data.message.ChatMessage> history,String memoryContext,
                               String projectId,String taskId) {
        ObjectNode input = input(fileIds, projectId, taskId);
        return runGeneral(objective, input, history, memoryContext);
    }

    /** Runs the same Agent using the inputs mapped into a DAG node. */
    public AgentRun runGeneralForWorkflow(String objective, JsonNode mappedInput) {
        ObjectNode input = mappedInput instanceof ObjectNode object ? object.deepCopy() : objectMapper.createObjectNode();
        JsonNode fileIds = input.path("fileIds");
        if (!fileIds.isMissingNode() && !fileIds.isNull()) {
            if (!fileIds.isArray() || fileIds.size() > 3) {
                throw new IllegalArgumentException("fileIds 必须为数组，附件最多3个");
            }
            for (JsonNode id : fileIds) {
                if (!id.isTextual() || !StringUtils.hasText(id.asText())) {
                    throw new IllegalArgumentException("fileIds 必须包含有效的文件 ID");
                }
            }
        }
        input.remove("knowledgeCapabilityId");
        if (fileIds.isArray() && !fileIds.isEmpty()) {
            input.put("knowledgeCapabilityId", KnowledgeCapabilityRegistrar.RAG_ANSWER);
        }
        return runGeneral(objective, input, List.of(), null);
    }

    /** 从能力目录筛选本轮可用的工具，并将通用提示词与可选会话上下文交给 Runtime。 */
    private AgentRun runGeneral(String objective, ObjectNode input,
                                List<dev.langchain4j.data.message.ChatMessage> history, String memoryContext) {
        var currentMcpIds = provisioningService.ensureAllEnabledMcpProviders().stream()
                .map(CapabilityDescriptor::id).collect(java.util.stream.Collectors.toSet());
        boolean hasFiles = input.path("fileIds").isArray() && !input.path("fileIds").isEmpty();
        List<CapabilityDescriptor> capabilities = capabilityRegistry.list().stream()
                .filter(CapabilityDescriptor::enabled)
                .filter(capability -> capability.type() != com.melon.agent.capability.CapabilityType.MCP_TOOL
                        || currentMcpIds.contains(capability.id()))
                .filter(capability -> hasFiles || !KnowledgeCapabilityRegistrar.RAG_ANSWER.equals(capability.id()))
                .filter(capability -> !KnowledgeCapabilityRegistrar.KNOWLEDGE_SEARCH.equals(capability.id()))
                .toList();
        return execute(GeneralAgentRunner.AGENT_ID, GENERAL_SYSTEM_PROMPT, objective, input, capabilities, history, memoryContext);
    }

    /**
     * 使用明确的 Capability ID 白名单运行 Agent。
     */
    public AgentRun runWithCapabilities(String agentId,
                                        String systemPrompt,
                                        String objective,
                                        Collection<String> capabilityIds,
                                        List<String> fileIds) {
        List<CapabilityDescriptor> capabilities = capabilityRegistry.findEnabled(capabilityIds);
        return execute(agentId, systemPrompt, objective, input(fileIds, null, null), capabilities, List.of(), null);
    }

    /**
     * 提取成功答案，或把未完成状态转换为稳定的业务错误文本。
     *
     * @param run Agent 运行结果
     * @return 可直接展示的文本
     */
    public String answer(AgentRun run) {
        if (run.getStatus() == AgentRun.Status.COMPLETED && StringUtils.hasText(run.getFinalAnswer())) {
            return run.getFinalAnswer();
        }
        return "Agent 执行失败: " + (StringUtils.hasText(run.getFailureReason())
                ? run.getFailureReason() : run.getStatus().name());
    }

    /** 将附件、项目和任务范围组装为本轮 Agent 的结构化输入。 */
    private ObjectNode input(List<String> fileIds, String projectId, String taskId) {
        ObjectNode input = objectMapper.createObjectNode();
        if (projectId != null) input.put("memoryProjectId", projectId);
        if (taskId != null) input.put("memoryTaskId", taskId);
        if (fileIds != null && !fileIds.isEmpty()) {
            input.set("fileIds", objectMapper.valueToTree(fileIds));
            input.put("knowledgeCapabilityId", KnowledgeCapabilityRegistrar.RAG_ANSWER);
        }
        return input;
    }

    /** 检查能力数量上限，组装 Agent 定义、目标和历史消息后交给 Runtime。 */
    private AgentRun execute(String agentId, String systemPrompt, String objective, ObjectNode input,
                             List<CapabilityDescriptor> capabilities,
                             List<dev.langchain4j.data.message.ChatMessage> history, String memoryContext) {
        int maximumCapabilities = properties.getMaximumCapabilities();
        if (capabilities.size() > maximumCapabilities) {
            throw new IllegalArgumentException("可用能力数量 " + capabilities.size()
                    + " 超过 Agent Runtime 上限 " + maximumCapabilities);
        }
        AgentDefinition definition = new AgentDefinition();
        definition.setId(agentId);
        definition.setName(agentId);
        definition.setSystemPrompt(systemPrompt);
        definition.setCapabilityIds(capabilities.stream()
                .map(CapabilityDescriptor::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));

        AgentRunRequest request = new AgentRunRequest();
        request.setDefinition(definition);
        request.setObjective(objective);
        request.setInput(input);
        request.setHistory(history);
        request.setMemoryContext(memoryContext);
        return agentRuntime.execute(request);
    }

}
