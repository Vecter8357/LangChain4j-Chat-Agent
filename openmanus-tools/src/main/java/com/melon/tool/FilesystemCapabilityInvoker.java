package com.melon.tool;

import com.melon.agent.runtime.CapabilityInvocation;
import com.melon.agent.runtime.CapabilityInvoker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.melon.agent.capability.CapabilityType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * 执行平台内置的本地文件系统能力。
 */
@Component
public class FilesystemCapabilityInvoker implements CapabilityInvoker {

    private final FilesystemTool filesystemTool;
    /** 注入负责路径与读权限检查的本地文件系统工具。 */
    public FilesystemCapabilityInvoker(FilesystemTool filesystemTool) {
        this.filesystemTool = filesystemTool;
    }

    /** {@inheritDoc} */
    @Override
    public Set<CapabilityType> supportedTypes() {
        return Set.of(CapabilityType.LOCAL_TOOL);
    }

    /**
     * {@inheritDoc}
     * 内部异常会转换为标准失败结果，不直接泄漏到 Agent Runtime。
     */
    @Override
    public CapabilityInvocation.Result invoke(CapabilityInvocation.Request request) {
        Instant startedAt = Instant.now();
        try {
            if (!"filesystem".equals(request.capability().providerId())) {
                throw new IllegalArgumentException("未知内置能力提供方: " + request.capability().providerId());
            }
            String output = filesystem(request.capability().providerOperation(), request.arguments());
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
     * 路由并执行只读文件系统操作。
     */
    private String filesystem(String operation, JsonNode arguments) {
        return switch (operation) {
            case "listDirectory" -> filesystemTool.listDirectory(requiredText(arguments, "dirPath"));
            case "readFile" -> filesystemTool.readFile(requiredText(arguments, "filePath"));
            case "searchFiles" -> filesystemTool.searchFiles(
                    requiredText(arguments, "dirPath"), requiredText(arguments, "keyword"));
            case "getFilesystemInfo" -> filesystemTool.getFilesystemInfo();
            default -> throw new IllegalArgumentException("未知文件系统操作: " + operation);
        };
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
