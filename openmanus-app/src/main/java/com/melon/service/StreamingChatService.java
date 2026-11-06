package com.melon.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 对话层 SSE 服务契约，兼容原有分类入口并统一委托 Agent 平台执行。
 */
public interface StreamingChatService {

    /**
     * 使用通用 Agent 处理问题，并按当前文件范围开放知识能力。
     */
    SseEmitter processAgentRequest(String input, List<String> fileIds);

    /**
     * 使用 GitHub Agent 处理问题。
     */
    SseEmitter processGitHubRequest(String input);

    /**
     * 使用网页抓取 Agent 处理问题。
     */
    SseEmitter processFetchRequest(String input);

    /**
     * 使用只读文件系统 Agent 处理问题。
     */
    SseEmitter processFilesystemRequestStream(String input);

    /**
     * 处理未指定工具的普通问题。
     */
    SseEmitter processOtherRequest(String input);

    /**
     * 使用限定文件集合的 Knowledge Agent 处理问题。
     */
    SseEmitter processRagRequest(String input, List<String> fileIds);

    /**
     * 从知识检索存储中移除指定文件。
     */
    void removeFileFromRag(String fileId);
}
