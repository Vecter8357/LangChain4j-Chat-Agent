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
@ConfigurationProperties(prefix = "tool-security")
public class ToolSecurityProperties {

    private GitHubProperties github = new GitHubProperties();

    private FetchProperties fetch = new FetchProperties();

    private FilesystemProperties filesystem = new FilesystemProperties();

    @Data
    public static class GitHubProperties {

        private boolean enabled = true;

        private String token;

        private Duration timeout = Duration.ofSeconds(120);

        private List<String> allowedRepositories = new ArrayList<>();

        private List<String> command = new ArrayList<>(Arrays.asList(
                "npx", "-y", "@modelcontextprotocol/server-github@latest"
        ));
    }

    @Data
    public static class FetchProperties {

        private boolean enabled = true;

        private Duration timeout = Duration.ofSeconds(120);

        private boolean debug = true;

        private List<String> allowedDomains = new ArrayList<>();

        private int maxResponseLength = 200_000;

        private List<String> command = new ArrayList<>(Arrays.asList("uvx", "mcp-server-fetch"));
    }

    @Data
    public static class FilesystemProperties {

        private boolean enabled = true;

        private List<String> allowedRoots = new ArrayList<>(List.of("./data"));

        private boolean allowWrite = false;

        private boolean allowDelete = false;

        private long maxReadBytes = 1024 * 1024;

        private int searchDepth = 5;

        private int maxSearchResults = 50;
    }

}
