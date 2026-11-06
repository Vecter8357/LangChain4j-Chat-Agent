package com.melon.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * MinIO 连接集合配置。当前文件存储使用列表中的第一个配置，后续可按名称扩展多存储路由。
 */
@Data
@Component
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {

    private List<Config> configs = new ArrayList<>();

    /**
     * 返回并校验主 MinIO 配置。
     */
    public Config primary() {
        if (configs == null || configs.isEmpty()) {
            throw new IllegalStateException("未配置 minio.configs");
        }
        Config config = configs.getFirst();
        if (!StringUtils.hasText(config.getEndpoint())
                || !StringUtils.hasText(config.getAccessKey())
                || !StringUtils.hasText(config.getSecretKey())
                || !StringUtils.hasText(config.getBucket())) {
            throw new IllegalStateException("minio.configs[0] 的 endpoint、accessKey、secretKey、bucket 均不能为空");
        }
        return config;
    }

    /** 单个 MinIO 服务连接配置。 */
    @Data
    public static class Config {
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String bucket;
    }
}
