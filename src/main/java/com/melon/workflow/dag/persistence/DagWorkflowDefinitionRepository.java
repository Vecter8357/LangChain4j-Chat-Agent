package com.melon.workflow.dag.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.config.properties.DagWorkflowProperties;
import com.melon.workflow.WorkflowExecutionException;
import com.melon.workflow.dag.model.DagWorkflowDesign;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 使用 Redis 保存工作流定义、画布位置及定义索引。
 */
@Repository
public class DagWorkflowDefinitionRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DagWorkflowProperties properties;

    /** 创建定义仓库。 */
    public DagWorkflowDefinitionRepository(StringRedisTemplate redisTemplate,
                                           ObjectMapper objectMapper,
                                           DagWorkflowProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** 保存或覆盖一个工作流设计。 */
    public DagWorkflowDesign save(DagWorkflowDesign design) {
        String code = design.getDefinition().getCode();
        try {
            redisTemplate.opsForValue().set(key(code), objectMapper.writeValueAsString(design));
            redisTemplate.opsForZSet().add(properties.getDefinitionIndexKey(), code,
                    design.getUpdatedAt().toEpochMilli());
            return design;
        } catch (Exception error) {
            throw new WorkflowExecutionException("工作流定义保存失败: " + code, error);
        }
    }

    /** 按编码读取工作流设计。 */
    public Optional<DagWorkflowDesign> findByCode(String code) {
        try {
            String value = redisTemplate.opsForValue().get(key(code));
            return value == null ? Optional.empty()
                    : Optional.of(objectMapper.readValue(value, DagWorkflowDesign.class));
        } catch (JsonProcessingException error) {
            throw new WorkflowExecutionException("工作流定义反序列化失败: " + code, error);
        } catch (Exception error) {
            throw new WorkflowExecutionException("工作流定义读取失败: " + code, error);
        }
    }

    /** 按更新时间倒序列出全部工作流设计。 */
    public List<DagWorkflowDesign> findAll() {
        try {
            Set<String> codes = redisTemplate.opsForZSet()
                    .reverseRange(properties.getDefinitionIndexKey(), 0, -1);
            if (codes == null || codes.isEmpty()) {
                return List.of();
            }
            return codes.stream().map(this::findByCode).flatMap(Optional::stream).toList();
        } catch (Exception error) {
            throw new WorkflowExecutionException("工作流定义列表读取失败", error);
        }
    }

    /** 删除指定工作流设计及索引。 */
    public boolean delete(String code) {
        try {
            Boolean deleted = redisTemplate.delete(key(code));
            redisTemplate.opsForZSet().remove(properties.getDefinitionIndexKey(), code);
            return Boolean.TRUE.equals(deleted);
        } catch (Exception error) {
            throw new WorkflowExecutionException("工作流定义删除失败: " + code, error);
        }
    }

    /** 生成定义存储 Key。 */
    private String key(String code) {
        return properties.getDefinitionKeyPrefix() + code;
    }
}
