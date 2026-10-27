package com.melon.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * @author: melon
 * @description: TODO
 * @date: 2025/3/18 13:41
 */
public interface StreamingChatService {

    public SseEmitter processGitHubRequest(String input);

    SseEmitter processFetchRequest(String input);

    SseEmitter processFilesystemRequestStream(String input);

    SseEmitter processOtherRequest(String input);

    SseEmitter processRagRequest(String input, List<String> fileIds);

    void removeFileFromRag(String fileId);
}
