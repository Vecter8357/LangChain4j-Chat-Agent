package com.melon.workflow.dag.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.config.properties.DagWorkflowProperties;
import com.melon.workflow.WorkflowExecutionException;
import com.melon.workflow.dag.model.DagWorkflowRun;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Repository
public class DagWorkflowRunRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DagWorkflowProperties properties;

    public DagWorkflowRunRepository(StringRedisTemplate redisTemplate,
                                    ObjectMapper objectMapper,
                                    DagWorkflowProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void save(DagWorkflowRun run) {
        run.setUpdatedAt(Instant.now());
        try {
            redisTemplate.opsForValue().set(
                    key(run.getRunId()),
                    objectMapper.writeValueAsString(run),
                    properties.getRunTtl().toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            throw new WorkflowExecutionException("DAG 运行状态写入 Redis 失败", error);
        }
    }

    public Optional<DagWorkflowRun> findById(String runId) {
        try {
            String value = redisTemplate.opsForValue().get(key(runId));
            return value == null
                    ? Optional.empty()
                    : Optional.of(objectMapper.readValue(value, DagWorkflowRun.class));
        } catch (JsonProcessingException error) {
            throw new WorkflowExecutionException("DAG 运行状态反序列化失败: " + runId, error);
        } catch (Exception error) {
            throw new WorkflowExecutionException("读取 DAG 运行状态失败: " + runId, error);
        }
    }

    private String key(String runId) {
        return properties.getRunKeyPrefix() + runId;
    }
}
