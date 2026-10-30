package com.melon.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.melon.agent.capability.BuiltInCapabilityRegistrar;
import com.melon.agent.capability.CapabilityDescriptor;
import com.melon.agent.capability.CapabilityProvisioningService;
import com.melon.agent.capability.CapabilityRegistry;
import com.melon.config.properties.AgentPlatformProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.time.Instant;

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
    private final AgentPlatformProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 创建平台 Agent 门面。
     */
    public PlatformAgentService(AgentRuntime agentRuntime,
                                CapabilityRegistry capabilityRegistry,
                                CapabilityProvisioningService provisioningService,
                                AgentPlatformProperties properties,
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
        provisioningService.ensureAllEnabledMcpProviders();
        boolean hasFiles = fileIds != null && !fileIds.isEmpty();
        List<CapabilityDescriptor> capabilities = capabilityRegistry.list().stream()
                .filter(CapabilityDescriptor::enabled)
                .filter(capability -> hasFiles || !BuiltInCapabilityRegistrar.RAG_ANSWER.equals(capability.id()))
                .filter(capability -> !BuiltInCapabilityRegistrar.KNOWLEDGE_SEARCH.equals(capability.id()))
                .toList();
        return execute("general-chat-agent", GENERAL_SYSTEM_PROMPT, objective, fileIds, capabilities);
    }

    /**
     * 使用指定 MCP 提供方的全部已发现能力运行 Agent。
     */
    public AgentRun runWithProviders(String agentId,
                                     String systemPrompt,
                                     String objective,
                                     Collection<String> providerIds,
                                     List<String> fileIds) {
        List<CapabilityDescriptor> capabilities = provisioningService.ensureMcpProviders(providerIds);
        if (providerIds != null && !providerIds.isEmpty() && capabilities.isEmpty()) {
            return failed(agentId, objective, "没有发现可用的 MCP 能力: " + providerIds);
        }
        return execute(agentId, systemPrompt, objective, fileIds, capabilities);
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
        return execute(agentId, systemPrompt, objective, fileIds, capabilities);
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

    /**
     * 构造 AgentDefinition 和结构化输入后交给 Agent Runtime 执行。
     */
    private AgentRun execute(String agentId,
                             String systemPrompt,
                             String objective,
                             List<String> fileIds,
                             List<CapabilityDescriptor> capabilities) {
        int maximumCapabilities = properties.getRuntime().getMaximumCapabilities();
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

        ObjectNode input = objectMapper.createObjectNode();
        if (fileIds != null && !fileIds.isEmpty()) {
            input.set("fileIds", objectMapper.valueToTree(fileIds));
            input.put("knowledgeCapabilityId", BuiltInCapabilityRegistrar.RAG_ANSWER);
        }
        AgentRunRequest request = new AgentRunRequest();
        request.setDefinition(definition);
        request.setObjective(objective);
        request.setInput(input);
        return agentRuntime.execute(request);
    }

    /**
     * 在没有可用能力等前置失败场景中创建标准失败运行结果。
     */
    private AgentRun failed(String agentId, String objective, String reason) {
        AgentRun run = new AgentRun();
        run.setRunId(java.util.UUID.randomUUID().toString());
        run.setAgentId(agentId);
        run.setObjective(objective);
        run.setStatus(AgentRun.Status.FAILED);
        run.setFailureReason(reason);
        run.setCreatedAt(Instant.now());
        run.setCompletedAt(Instant.now());
        return run;
    }
}
