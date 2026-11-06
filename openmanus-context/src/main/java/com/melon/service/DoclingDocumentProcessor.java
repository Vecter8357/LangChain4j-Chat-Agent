package com.melon.service;

import com.melon.config.properties.DoclingProperties;
import com.melon.entity.UploadFile;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Slf4j
@Component
public class DoclingDocumentProcessor {

    private final DoclingProperties properties;

    /** 保存 Docling 命令、超时、OCR 和文件处理配置。 */
    public DoclingDocumentProcessor(DoclingProperties properties) {
        this.properties = properties;
    }

    /**
     * 将已经从对象存储物化到本地的临时文件交给 Docling 转换。
     */
    public Optional<Document> convertToDocument(UploadFile file, Path sourcePath, String fileExtension) {
        if (!properties.isEnabled() || !isSupported(fileExtension)) {
            return Optional.empty();
        }

        if (!Files.exists(sourcePath)) {
            log.warn("Docling skipped because source file does not exist: {}", sourcePath);
            return Optional.empty();
        }
        try {
            long fileSize = Files.size(sourcePath);
            if (fileSize > properties.getMaxInputBytes()) {
                log.warn("Docling skipped because file is too large: {}, size={}", sourcePath, fileSize);
                return Optional.empty();
            }
        } catch (IOException e) {
            log.warn("Docling skipped because file size cannot be read: {}", sourcePath, e);
            return Optional.empty();
        }

        Path outputDir = Paths.get(System.getProperty("java.io.tmpdir"), "langchain4j-docling", UUID.randomUUID().toString());
        try {
            Files.createDirectories(outputDir);
            ConversionResult result = convert(sourcePath, outputDir, true);
            if (!result.success()) {
                result = convert(sourcePath, outputDir, false);
            }
            if (!result.success()) {
                log.warn("Docling conversion failed for {}. Output: {}", file.getOriginalFileName(), result.output());
                return Optional.empty();
            }

            Optional<Path> markdownFile = findMarkdownFile(outputDir);
            if (markdownFile.isEmpty()) {
                log.warn("Docling conversion finished but no markdown file was generated for {}", file.getOriginalFileName());
                return Optional.empty();
            }

            String markdown = Files.readString(markdownFile.get(), StandardCharsets.UTF_8);
            if (!StringUtils.hasText(markdown)) {
                log.warn("Docling generated empty markdown for {}", file.getOriginalFileName());
                return Optional.empty();
            }

            Metadata metadata = new Metadata()
                    .put(Document.FILE_NAME, file.getOriginalFileName())
                    .put("source", "docling")
                    .put("file_id", file.getId());
            log.info("Docling converted {} to markdown: {}", file.getOriginalFileName(), markdownFile.get());
            return Optional.of(Document.from(markdown, metadata));
        } catch (Exception e) {
            log.warn("Docling conversion failed for {}", file.getOriginalFileName(), e);
            return Optional.empty();
        } finally {
            deleteQuietly(outputDir);
        }
    }

    /** 返回 Docling 解析失败后是否允许回退到原生解析器。 */
    public boolean shouldFallbackToNativeParser() {
        return properties.isFallbackToNativeParser();
    }

    /** 忽略大小写检查文件扩展名是否在 Docling 支持列表内。 */
    private boolean isSupported(String fileExtension) {
        if (!StringUtils.hasText(fileExtension)) {
            return false;
        }
        String normalized = fileExtension.toLowerCase(Locale.ROOT);
        return properties.getSupportedExtensions().stream()
                .map(extension -> extension.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::equals);
    }

    /** 启动 Docling 子进程，读取合并日志并根据超时或退出状态返回转换结果。 */
    private ConversionResult convert(Path sourcePath, Path outputDir, boolean useConvertSubcommand)
            throws IOException, InterruptedException {
        List<String> command = buildCommand(sourcePath, outputDir, useConvertSubcommand);

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

        Duration timeout = properties.getTimeout();
        boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        if (!completed) {
            process.destroyForcibly();
            return new ConversionResult(false, "Docling timed out after " + timeout + ". Output: " + output);
        }
        return new ConversionResult(process.exitValue() == 0, output);
    }

    /** 组装 Markdown 转换命令，并按配置附加 OCR、语言和引擎参数。 */
    private List<String> buildCommand(Path sourcePath, Path outputDir, boolean useConvertSubcommand) {
        List<String> command = new java.util.ArrayList<>();
        command.add(properties.getCommand());
        if (useConvertSubcommand) {
            command.add("convert");
        }
        command.add(sourcePath.toString());
        command.add("--to");
        command.add("md");
        command.add("--output");
        command.add(outputDir.toString());

        if (properties.isOcrEnabled()) {
            command.add("--ocr");
            if (StringUtils.hasText(properties.getOcrEngine())) {
                command.add("--ocr-engine");
                command.add(properties.getOcrEngine());
            }
            if (StringUtils.hasText(properties.getOcrLang())) {
                command.add("--ocr-lang");
                command.add(properties.getOcrLang());
            }
        } else {
            command.add("--no-ocr");
        }
        return command;
    }

    /** 在输出目录中寻找最近修改的 Markdown 文件。 */
    private Optional<Path> findMarkdownFile(Path outputDir) throws IOException {
        try (Stream<Path> files = Files.walk(outputDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .max(Comparator.comparing(path -> path.toFile().lastModified()));
        }
    }

    /** 从子路径向上清理转换临时目录，清理失败时记录调试日志。 */
    private void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(currentPath -> {
                        try {
                            Files.deleteIfExists(currentPath);
                        } catch (IOException e) {
                            log.debug("Failed to delete temporary Docling file: {}", currentPath, e);
                        }
                    });
        } catch (IOException e) {
            log.debug("Failed to clean temporary Docling directory: {}", path, e);
        }
    }

    private record ConversionResult(boolean success, String output) {
    }
}
