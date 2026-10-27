package com.melon.config;

import com.melon.entity.UploadFile;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonConfigTest {

    @Test
    void redisSerializerRoundTripsApplicationTypesWithoutUnrestrictedAutoType() {
        RedisSerializer<Object> serializer = JacksonConfig.redisValueSerializer();
        UploadFile original = UploadFile.builder()
                .id("file-1")
                .originalFileName("article.txt")
                .storedFileName("file-1.txt")
                .filePath("data/file-1.txt")
                .size(42)
                .fileType("text/plain")
                .uploadTime(LocalDateTime.of(2026, 10, 27, 15, 0))
                .loadedToRag(true)
                .build();

        Object restored = serializer.deserialize(serializer.serialize(original));

        assertThat(restored).isEqualTo(original);
    }
}
