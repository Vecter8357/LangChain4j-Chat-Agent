package com.melon.service.impl;

import com.melon.agent.capability.BuiltInCapabilityRegistrar;
import com.melon.agent.runtime.AgentRun;
import com.melon.agent.runtime.PlatformAgentService;
import com.melon.service.RagService;
import com.melon.service.StreamingChatService;
import com.melon.tool.FetchTool;
import com.melon.tool.GitHubTool;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 对话 SSE 服务实现。所有业务入口均委托统一 Agent 平台执行，完成后发送最终文本。
 */
@AllArgsConstructor
@Slf4j
@Service
public class StreamingChatServiceImpl implements StreamingChatService {

    private static final String FILESYSTEM_PROMPT = """
            你是文件系统助手。只能使用提供的只读文件系统能力，并严格遵守允许目录限制。
            对文件内容进行总结时要明确文件来源。
            """;
    private static final String KNOWLEDGE_PROMPT = """
            你是文档知识库助手。必须使用 Knowledge 能力查询当前请求指定的文件，
            根据文档内容回答，不得补充文档中没有的事实。
            """;

    private final GitHubTool gitHubTool;
    private final FetchTool fetchTool;
    private final PlatformAgentService platformAgentService;
    private final RagService ragService;

    /** {@inheritDoc} */
    @Override
    public SseEmitter processAgentRequest(String input, List<String> fileIds) {
        return emit(() -> platformAgentService.answer(platformAgentService.runGeneral(input, fileIds)));
    }

    /** {@inheritDoc} */
    @Override
    public SseEmitter processGitHubRequest(String input) {
        return emit(() -> gitHubTool.askGitHub(input));
    }

    /** {@inheritDoc} */
    @Override
    public SseEmitter processFetchRequest(String input) {
        return emit(() -> fetchTool.fetchAndAnswer(input));
    }

    /** {@inheritDoc} */
    @Override
    public SseEmitter processFilesystemRequestStream(String input) {
        return emit(() -> {
            AgentRun run = platformAgentService.runWithCapabilities(
                    "filesystem-agent",
                    FILESYSTEM_PROMPT,
                    input,
                    List.of(
                            "local.filesystem.list_directory",
                            "local.filesystem.read_file",
                            "local.filesystem.search_files",
                            "local.filesystem.info"),
                    List.of());
            return platformAgentService.answer(run);
        });
    }

    /** {@inheritDoc} */
    @Override
    public SseEmitter processOtherRequest(String input) {
        return processAgentRequest(input, List.of());
    }

    /** {@inheritDoc} */
    @Override
    public SseEmitter processRagRequest(String input, List<String> fileIds) {
        return emit(() -> {
            AgentRun run = platformAgentService.runWithCapabilities(
                    "knowledge-agent", KNOWLEDGE_PROMPT, input,
                    List.of(BuiltInCapabilityRegistrar.RAG_ANSWER), fileIds);
            return platformAgentService.answer(run);
        });
    }

    /** {@inheritDoc} */
    @Override
    public void removeFileFromRag(String fileId) {
        ragService.removeFileFromRag(fileId);
    }

    /**
     * 异步执行阻塞型 Agent 任务，并将最终结果写入 SSE。
     */
    private SseEmitter emit(Supplier<String> task) {
        SseEmitter emitter = new SseEmitter(-1L);
        CompletableFuture.runAsync(() -> {
            try {
                emitter.send(task.get());
                emitter.complete();
            } catch (Exception error) {
                log.error("统一 Agent 请求执行失败", error);
                emitter.completeWithError(error);
            }
        });
        return emitter;
    }
}
