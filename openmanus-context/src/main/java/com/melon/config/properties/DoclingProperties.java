package com.melon.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "tool-security.docling")
public class DoclingProperties {
    private boolean enabled = true;

    private String command = ".venv-docling/Scripts/docling.exe";

    private Duration timeout = Duration.ofMinutes(5);

    private List<String> supportedExtensions = new ArrayList<>(Arrays.asList(
            "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "html", "md"
    ));

    private boolean fallbackToNativeParser = true;

    private long maxInputBytes = 50L * 1024 * 1024;

    private boolean ocrEnabled = true;

    private String ocrEngine;

    private String ocrLang;
}
