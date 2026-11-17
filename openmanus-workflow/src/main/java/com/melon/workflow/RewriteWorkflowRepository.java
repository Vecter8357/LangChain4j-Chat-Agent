package com.melon.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.config.properties.RewriteWorkflowProperties;
import com.melon.workflow.model.RewriteWorkflowState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Repository
public class RewriteWorkflowRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RewriteWorkflowProperties properties;

    /** 装配用于持久化改编检查点的 Redis 模板与 JSON 映射器。 */
    public RewriteWorkflowRepository(StringRedisTemplate redisTemplate,
                                     ObjectMapper objectMapper,
                                     RewriteWorkflowProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** 按持久化配置保存带有效期的改编状态快照。 */
    public void save(RewriteWorkflowState state) {
        state.setUpdatedAt(Instant.now());
        state.setPersisted(true);
        state.setPersistenceError(null);
        try {
            String value = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(
                    key(state.getWorkflowId()),
                    value,
                    properties.getPersistence().getTtl().toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (Exception e) {
            state.setPersisted(false);
            state.setPersistenceError(rootMessage(e));
            log.error("保存改编工作流 {} 失败", state.getWorkflowId(), e);
            if (properties.getPersistence().isRequired()) {
                throw new WorkflowExecutionException("工作流检查点写入 Redis 失败", e);
            }
        }
    }

    /** 查询 Redis 检查点并恢复对应的改编状态。 */
    public Optional<RewriteWorkflowState> findById(String workflowId) {
        try {
            String value = redisTemplate.opsForValue().get(key(workflowId));
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, RewriteWorkflowState.class));
        } catch (JsonProcessingException e) {
            throw new WorkflowExecutionException("工作流状态反序列化失败: " + workflowId, e);
        } catch (Exception e) {
            throw new WorkflowExecutionException("读取工作流状态失败: " + workflowId, e);
        }
    }

    /** 将配置的 Redis 键前缀和改编工作流 ID 拼接为存储键。 */
    private String key(String workflowId) {
        return properties.getPersistence().getKeyPrefix() + workflowId;
    }

    /** 从异常链中提取底层消息，供检查点错误日志使用。 */
    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
