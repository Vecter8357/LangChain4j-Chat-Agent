package com.melon.knowledge;

import com.melon.agent.runtime.CapabilityInvocation;
import com.melon.agent.runtime.CapabilityInvoker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.melon.agent.capability.CapabilityType;
import com.melon.service.RagService;
import com.melon.knowledge.KnowledgeModels.SearchRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 执行临时附件和永久知识库的检索能力。
 */
@Component
public class KnowledgeCapabilityInvoker implements CapabilityInvoker {

    private final RagService ragService;
    private final KnowledgeSearchService knowledgeSearch;
    private final ObjectMapper objectMapper;

    /**
     * 创建内置能力调用器。
     */
    public KnowledgeCapabilityInvoker(RagService ragService,
                                    KnowledgeSearchService knowledgeSearch, ObjectMapper objectMapper) {
        this.ragService = ragService;
        this.knowledgeSearch = knowledgeSearch;
        this.objectMapper = objectMapper;
    }

    /** {@inheritDoc} */
    @Override
    public Set<CapabilityType> supportedTypes() {
        return Set.of(CapabilityType.KNOWLEDGE);
    }

    /**
     * {@inheritDoc}
     * 内部异常会转换为标准失败结果，不直接泄漏到 Agent Runtime。
     */
    @Override
    public CapabilityInvocation.Result invoke(CapabilityInvocation.Request request) {
        Instant startedAt = Instant.now();
        try {
            if ("knowledge".equals(request.capability().providerId()) && "search".equals(request.capability().providerOperation())) {
                List<String> bases = new ArrayList<>(), files = new ArrayList<>();
                request.arguments().path("knowledgeBaseIds").forEach(item -> bases.add(item.asText()));
                request.arguments().path("fileIds").forEach(item -> files.add(item.asText()));
                var result = knowledgeSearch.search(new SearchRequest(requiredText(request.arguments(),"question"),bases,files,null));
                return new CapabilityInvocation.Result(true,objectMapper.valueToTree(result),null,null,false,
                        Duration.between(startedAt,Instant.now()).toMillis());
            }
            if (!"knowledge".equals(request.capability().providerId())) {
                throw new IllegalArgumentException("未知内置能力提供方: " + request.capability().providerId());
            }
            String output = knowledge(request.capability().providerOperation(), request.arguments());
            boolean successful = output != null
                    && !output.startsWith("错误")
                    && !output.startsWith("回答问题失败")
                    && !output.startsWith("加载文件失败");
            long duration = Duration.between(startedAt, Instant.now()).toMillis();
            return successful
                    ? new CapabilityInvocation.Result(true, TextNode.valueOf(output), null, null, false, duration)
                    : new CapabilityInvocation.Result(false, TextNode.valueOf(output), "BUILTIN_TOOL_ERROR",
                    output, false, duration);
        } catch (Exception error) {
            return new CapabilityInvocation.Result(false, null, "BUILTIN_INVOCATION_FAILED",
                    rootMessage(error), false, Duration.between(startedAt, Instant.now()).toMillis());
        }
    }

    /**
     * 执行限定 fileIds 范围的知识库检索。
     */
    private String knowledge(String operation, JsonNode arguments) {
        if (!"answer".equals(operation)) {
            throw new IllegalArgumentException("未知知识库操作: " + operation);
        }
        List<String> fileIds = new ArrayList<>();
        arguments.path("fileIds").forEach(item -> fileIds.add(item.asText()));
        if (fileIds.isEmpty()) {
            throw new IllegalArgumentException("知识库能力必须提供 fileIds");
        }
        return ragService.retrieveContext(requiredText(arguments, "question"), fileIds);
    }

    /**
     * 读取必填字符串参数。
     */
    private String requiredText(JsonNode arguments, String field) {
        String value = arguments == null ? null : arguments.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    /**
     * 提取异常链最底层信息。
     */
    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
