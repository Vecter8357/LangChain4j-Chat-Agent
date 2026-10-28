package com.melon.workflow.dag.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.config.properties.DagWorkflowProperties;
import com.melon.workflow.WorkflowExecutionException;
import com.melon.workflow.dag.model.DagWorkflowRun;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
            redisTemplate.opsForZSet().add(properties.getRunIndexKey(), run.getRunId(),
                    run.getCreatedAt() == null ? System.currentTimeMillis() : run.getCreatedAt().toEpochMilli());
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

    /**
     * 按创建时间倒序返回最近运行，可按工作流编码过滤。
     *
     * @param workflowCode 可选工作流编码
     * @param limit 最大返回数量
     * @return 最近运行快照
     */
    public List<DagWorkflowRun> findRecent(String workflowCode, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        try {
            Set<String> runIds = redisTemplate.opsForZSet()
                    .reverseRange(properties.getRunIndexKey(), 0, Math.max(safeLimit * 4L, safeLimit) - 1);
            if (runIds == null || runIds.isEmpty()) {
                return List.of();
            }
            return runIds.stream()
                    .map(this::findById)
                    .flatMap(Optional::stream)
                    .filter(run -> workflowCode == null || workflowCode.isBlank()
                            || workflowCode.equals(run.getWorkflowCode()))
                    .limit(safeLimit)
                    .toList();
        } catch (Exception error) {
            throw new WorkflowExecutionException("读取 DAG 运行历史失败", error);
        }
    }

    private String key(String runId) {
        return properties.getRunKeyPrefix() + runId;
    }
}
