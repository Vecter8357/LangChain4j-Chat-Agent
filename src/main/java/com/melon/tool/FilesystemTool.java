package com.melon.tool;

import com.melon.config.properties.ToolSecurityProperties;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 文件系统工具
 * 提供文件系统操作功能
 */
@Component
@Slf4j
public class FilesystemTool {

    private final ToolSecurityProperties.FilesystemProperties properties;

    /**
     * 初始化允许访问的根目录列表
     */
    public FilesystemTool(ToolSecurityProperties toolSecurityProperties) {
        this.properties = toolSecurityProperties.getFilesystem();
        log.info("文件系统工具初始化完成，允许访问的根目录: {}, 允许写入: {}, 允许删除: {}", 
                properties.getAllowedRoots(), properties.isAllowWrite(), properties.isAllowDelete());
    }

    /**
     * 列出目录内容
     *
     * @param dirPath 目录路径
     * @return 目录内容列表
     */
    @Tool("列出指定目录下的文件和子目录")
    public String listDirectory(String dirPath) {
        log.info("列出目录内容: {}", dirPath);
        
        if (!properties.isEnabled()) {
            return "错误: 文件系统工具已禁用";
        }

        try {
            Path path = validateAndGetPath(dirPath);
            if (!Files.isDirectory(path)) {
                return "错误: 指定路径不是目录: " + dirPath;
            }

            StringBuilder result = new StringBuilder();
            result.append("目录: ").append(path.toAbsolutePath()).append("\n\n");

            try (Stream<Path> paths = Files.list(path)) {
                List<String> files = new ArrayList<>();
                List<String> directories = new ArrayList<>();

                paths.forEach(p -> {
                    String name = p.getFileName().toString();
                    if (Files.isDirectory(p)) {
                        directories.add(name + "/");
                    } else {
                        try {
                            long size = Files.size(p);
                            String sizeStr = formatFileSize(size);
                            files.add(name + " (" + sizeStr + ")");
                        } catch (IOException e) {
                            files.add(name + " (无法获取大小)");
                        }
                    }
                });

                if (!directories.isEmpty()) {
                    result.append("目录:\n");
                    directories.stream().sorted().forEach(d -> result.append("- ").append(d).append("\n"));
                    result.append("\n");
                }

                if (!files.isEmpty()) {
                    result.append("文件:\n");
                    files.stream().sorted().forEach(f -> result.append("- ").append(f).append("\n"));
                }

                if (directories.isEmpty() && files.isEmpty()) {
                    result.append("目录为空");
                }
            }

            return result.toString();
        } catch (Exception e) {
            log.error("列出目录内容时出错", e);
            return "错误: " + e.getMessage();
        }
    }

    /**
     * 读取文件内容
     *
     * @param filePath 文件路径
     * @return 文件内容
     */
    @Tool("读取指定文件的内容")
    public String readFile(String filePath) {
        log.info("读取文件内容: {}", filePath);

        if (!properties.isEnabled()) {
            return "错误: 文件系统工具已禁用";
        }

        try {
            Path path = validateAndGetPath(filePath);
            if (!Files.isRegularFile(path)) {
                return "错误: 指定路径不是文件: " + filePath;
            }

            long fileSize = Files.size(path);
            if (fileSize > properties.getMaxReadBytes()) {
                return "错误: 文件过大，无法读取。文件大小: " + formatFileSize(fileSize);
            }

            String content = Files.readString(path);
            return "文件: " + path.toAbsolutePath() + "\n\n" + content;
        } catch (Exception e) {
            log.error("读取文件内容时出错", e);
            return "错误: " + e.getMessage();
        }
    }

    /**
     * 写入文件内容
     *
     * @param filePath 文件路径
     * @param content  文件内容
     * @return 操作结果
     */
    @Tool("写入内容到指定文件")
    public String writeFile(String filePath, String content) {
        log.info("写入文件内容: {}", filePath);

        if (!properties.isEnabled()) {
            return "错误: 文件系统工具已禁用";
        }

        if (!properties.isAllowWrite()) {
            return "错误: 写入操作已禁用";
        }

        try {
            Path path = validateAndGetPath(filePath);
            
            // 确保父目录存在
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Files.writeString(path, content);
            return "成功: 内容已写入文件 " + path.toAbsolutePath();
        } catch (Exception e) {
            log.error("写入文件内容时出错", e);
            return "错误: " + e.getMessage();
        }
    }

    /**
     * 删除文件或目录
     *
     * @param path 文件或目录路径
     * @return 操作结果
     */
    @Tool("删除指定的文件或目录")
    public String delete(String path) {
        log.info("删除文件或目录: {}", path);

        if (!properties.isEnabled()) {
            return "错误: 文件系统工具已禁用";
        }

        if (!properties.isAllowDelete()) {
            return "错误: 删除操作已禁用";
        }

        try {
            Path filePath = validateAndGetPath(path);
            if (!Files.exists(filePath)) {
                return "错误: 指定路径不存在: " + path;
            }

            if (Files.isDirectory(filePath)) {
                try (Stream<Path> pathStream = Files.walk(filePath)) {
                    pathStream.sorted((p1, p2) -> -p1.compareTo(p2)) // 逆序，先删除子文件
                            .forEach(p -> {
                                try {
                                    Files.delete(p);
                                } catch (IOException e) {
                                    log.error("删除路径时出错: {}", p, e);
                                }
                            });
                }
                return "成功: 目录已删除 " + filePath.toAbsolutePath();
            } else {
                Files.delete(filePath);
                return "成功: 文件已删除 " + filePath.toAbsolutePath();
            }
        } catch (Exception e) {
            log.error("删除文件或目录时出错", e);
            return "错误: " + e.getMessage();
        }
    }

    /**
     * 搜索文件
     *
     * @param dirPath 目录路径
     * @param keyword 关键字
     * @return 搜索结果
     */
    @Tool("在指定目录中搜索包含关键字的文件")
    public String searchFiles(String dirPath, String keyword) {
        log.info("搜索文件: 目录={}, 关键字={}", dirPath, keyword);

        if (!properties.isEnabled()) {
            return "错误: 文件系统工具已禁用";
        }

        if (keyword == null || keyword.trim().isEmpty()) {
            return "错误: 搜索关键字不能为空";
        }

        try {
            Path path = validateAndGetPath(dirPath);
            if (!Files.isDirectory(path)) {
                return "错误: 指定路径不是目录: " + dirPath;
            }

            final String finalKeyword = keyword.toLowerCase();
            List<String> results = new ArrayList<>();

            try (Stream<Path> pathStream = Files.walk(path, properties.getSearchDepth())) {
                pathStream
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String fileName = p.getFileName().toString().toLowerCase();
                            return fileName.contains(finalKeyword);
                        })
                        .limit(properties.getMaxSearchResults())
                        .forEach(p -> results.add(path.relativize(p).toString()));
            }

            if (results.isEmpty()) {
                return "在目录 " + path.toAbsolutePath() + " 中未找到包含关键字 '" + keyword + "' 的文件";
            }

            StringBuilder result = new StringBuilder();
            result.append("在目录 ").append(path.toAbsolutePath())
                    .append(" 中找到 ").append(results.size()).append(" 个包含关键字 '")
                    .append(keyword).append("' 的文件:\n\n");

            results.stream().sorted().forEach(f -> result.append("- ").append(f).append("\n"));

            return result.toString();
        } catch (Exception e) {
            log.error("搜索文件时出错", e);
            return "错误: " + e.getMessage();
        }
    }

    /**
     * 获取文件系统信息
     *
     * @return 文件系统信息
     */
    @Tool("获取文件系统信息，包括总空间、可用空间等")
    public String getFilesystemInfo() {
        log.info("获取文件系统信息");

        if (!properties.isEnabled()) {
            return "错误: 文件系统工具已禁用";
        }

        try {
            StringBuilder result = new StringBuilder();
            result.append("文件系统信息:\n\n");

            for (String root : properties.getAllowedRoots()) {
                Path path = Paths.get(root);
                if (Files.exists(path)) {
                    File file = path.toFile();
                    long totalSpace = file.getTotalSpace();
                    long freeSpace = file.getFreeSpace();
                    long usableSpace = file.getUsableSpace();

                    result.append("路径: ").append(path.toAbsolutePath()).append("\n");
                    result.append("- 总空间: ").append(formatFileSize(totalSpace)).append("\n");
                    result.append("- 可用空间: ").append(formatFileSize(usableSpace)).append("\n");
                    result.append("- 已用空间: ").append(formatFileSize(totalSpace - freeSpace)).append("\n");
                    result.append("- 使用率: ").append(String.format("%.2f%%", (double) (totalSpace - freeSpace) / totalSpace * 100)).append("\n\n");
                }
            }

            return result.toString();
        } catch (Exception e) {
            log.error("获取文件系统信息时出错", e);
            return "错误: " + e.getMessage();
        }
    }

    /**
     * 验证并获取路径
     *
     * @param pathStr 路径字符串
     * @return 验证后的路径
     * @throws IOException 如果路径无效或不在允许的根目录下
     */
    private Path validateAndGetPath(String pathStr) throws IOException {
        if (pathStr == null || pathStr.trim().isEmpty()) {
            throw new IOException("路径不能为空");
        }

        Path path = Paths.get(pathStr);
        
        // 如果是相对路径，则使用第一个允许的根目录作为基础
        if (!path.isAbsolute()) {
            List<String> allowedRoots = properties.getAllowedRoots();
            if (allowedRoots == null || allowedRoots.isEmpty()) {
                throw new IOException("未配置允许访问的根目录");
            }
            path = Paths.get(allowedRoots.get(0), pathStr);
        }

        // 规范化路径
        path = path.normalize().toAbsolutePath();

        // 检查路径是否在允许的根目录下
        boolean isAllowed = false;
        for (String root : properties.getAllowedRoots()) {
            Path rootPath = Paths.get(root).normalize().toAbsolutePath();
            if (path.startsWith(rootPath)) {
                isAllowed = true;
                break;
            }
        }

        if (!isAllowed) {
            throw new IOException("访问被拒绝: 路径不在允许的根目录下: " + path);
        }

        return path;
    }

    /**
     * 格式化文件大小
     *
     * @param size 文件大小（字节）
     * @return 格式化后的文件大小
     */
    private String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.2f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", size / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", size / (1024.0 * 1024 * 1024));
        }
    }
}
