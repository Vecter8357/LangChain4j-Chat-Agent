package com.melon.workflow.dag;

import com.melon.workflow.dag.engine.DagWorkflowCompiler;
import com.melon.workflow.dag.model.DagWorkflowDesign;
import com.melon.workflow.dag.persistence.DagWorkflowDefinitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 管理可视化设计器的工作流草稿，并在保存前执行完整编译校验。
 */
@Service
public class DagWorkflowDefinitionService {

    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{1,79}");

    private final DagWorkflowDefinitionRepository repository;
    private final DagWorkflowCompiler compiler;

    /** 创建工作流定义服务。 */
    public DagWorkflowDefinitionService(DagWorkflowDefinitionRepository repository,
                                        DagWorkflowCompiler compiler) {
        this.repository = repository;
        this.compiler = compiler;
    }

    /** 保存经过校验的设计，保留首次创建时间。 */
    public DagWorkflowDesign save(String code, DagWorkflowDesign design) {
        if (design == null || design.getDefinition() == null) {
            throw new IllegalArgumentException("definition 不能为空");
        }
        if (!StringUtils.hasText(code) || !CODE_PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException("工作流 code 只能包含字母、数字、下划线和连字符，长度 2-80");
        }
        if (StringUtils.hasText(design.getDefinition().getCode())
                && !code.equals(design.getDefinition().getCode())) {
            throw new IllegalArgumentException("路径 code 与 definition.code 不一致");
        }
        design.getDefinition().setCode(code);
        compiler.compile(design.getDefinition());
        Instant now = Instant.now();
        repository.findByCode(code).ifPresentOrElse(
                existing -> design.setCreatedAt(existing.getCreatedAt()),
                () -> design.setCreatedAt(now));
        design.setUpdatedAt(now);
        if (design.getStatus() == null) {
            design.setStatus(DagWorkflowDesign.DesignStatus.DRAFT);
        }
        return repository.save(design);
    }

    /** 查询一个设计。 */
    public Optional<DagWorkflowDesign> findByCode(String code) {
        return StringUtils.hasText(code) ? repository.findByCode(code.trim()) : Optional.empty();
    }

    /** 查询全部设计。 */
    public List<DagWorkflowDesign> findAll() {
        return repository.findAll();
    }

    /** 删除一个设计。 */
    public boolean delete(String code) {
        return StringUtils.hasText(code) && repository.delete(code.trim());
    }
}
