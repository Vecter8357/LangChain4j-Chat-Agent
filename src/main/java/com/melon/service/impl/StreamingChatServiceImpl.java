package com.melon.service.impl;

import com.melon.service.RagService;
import com.melon.service.StreamingChatService;
import com.melon.tool.FetchTool;
import com.melon.tool.FilesystemTool;
import com.melon.tool.GitHubTool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.melon.prompt.Langchain4jPrompt.MCP_RESULT;
import static com.melon.prompt.Langchain4jPrompt.RAG_QUERY;

/**
 * @author: melon
 * @description: TODO
 * @date: 2025/3/18 13:45
 */
@AllArgsConstructor
@Slf4j
@Service
public class StreamingChatServiceImpl implements StreamingChatService {
    private final GitHubTool gitHubTool;
    private final FetchTool fetchTool;
    private final FilesystemTool filesystemTool;
    private final ChatModel chatLanguageModel;
    private final StreamingChatModel streamingChatLanguageModel;
    private final RagService ragService;

    @Override
    public SseEmitter processGitHubRequest(String input) {
        String result = gitHubTool.askGitHub(input);
        // -1 表示不超时
        SseEmitter emitter = new SseEmitter(-1L);
        CompletableFuture<ChatResponse> futureResponse = new CompletableFuture<>();
        streamingChatLanguageModel.chat(String.format(MCP_RESULT, input, result), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                try {
                    emitter.send(partialResponse);
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                futureResponse.complete(completeResponse);
                emitter.complete();
            }

            @Override
            public void onError(Throwable error) {
                futureResponse.completeExceptionally(error);
                emitter.completeWithError(error);
            }
        });
        return emitter;
    }

    @Override
    public SseEmitter processFetchRequest(String input) {
        String result = fetchTool.fetchAndAnswer(input);
        // -1 表示不超时
        SseEmitter emitter = new SseEmitter(-1L);
        CompletableFuture<ChatResponse> futureResponse = new CompletableFuture<>();
        streamingChatLanguageModel.chat(String.format(MCP_RESULT, input, result), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                try {
                    emitter.send(partialResponse);
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                futureResponse.complete(completeResponse);
                emitter.complete();
            }

            @Override
            public void onError(Throwable error) {
                futureResponse.completeExceptionally(error);
                emitter.completeWithError(error);
            }
        });
        return emitter;
    }

    @Override
    public SseEmitter processFilesystemRequestStream(String input) {
        SseEmitter emitter = new SseEmitter(-1L);

        CompletableFuture.runAsync(() -> {
            try {
                FilesystemAssistant assistant = AiServices.builder(FilesystemAssistant.class)
                        .chatModel(chatLanguageModel)
                        .tools(filesystemTool)
                        .build();
                emitter.send(assistant.chat(input));
                emitter.complete();
            } catch (Exception e) {
                log.error("处理文件系统请求失败", e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @Override
    public SseEmitter processOtherRequest(String input) {
        // -1 表示不超时
        SseEmitter emitter = new SseEmitter(-1L);
        CompletableFuture<ChatResponse> futureResponse = new CompletableFuture<>();
        streamingChatLanguageModel.chat(input, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                try {
                    emitter.send(partialResponse);
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                futureResponse.complete(completeResponse);
                emitter.complete();
            }

            @Override
            public void onError(Throwable error) {
                futureResponse.completeExceptionally(error);
                emitter.completeWithError(error);
            }
        });
        return emitter;
    }

    @Override
    public SseEmitter processRagRequest(String input, List<String> fileIds) {
        String result = ragService.answerWithRag(input, fileIds);
        // -1 表示不超时
        SseEmitter emitter = new SseEmitter(-1L);
        CompletableFuture<ChatResponse> futureResponse = new CompletableFuture<>();
        streamingChatLanguageModel.chat(String.format(RAG_QUERY, result, input), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                try {
                    emitter.send(partialResponse);
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                futureResponse.complete(completeResponse);
                emitter.complete();
            }

            @Override
            public void onError(Throwable error) {
                futureResponse.completeExceptionally(error);
                emitter.completeWithError(error);
            }
        });
        return emitter;
    }

    @Override
    public void removeFileFromRag(String fileId) {
        ragService.removeFileFromRag(fileId);
    }

    interface FilesystemAssistant {
        String chat(@UserMessage String message);
    }
}
