package com.melon.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private SplitterProperties splitter = new SplitterProperties();

    @Data
    public static class SplitterProperties {

        private int maxSegmentSize = 800;

        private int maxOverlapSize = 120;

        private int maxResults = 6;

        private double minScore = 0.55;
    }
}
