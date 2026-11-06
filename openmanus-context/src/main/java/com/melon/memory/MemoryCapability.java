package com.melon.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.melon.agent.capability.*;
import com.melon.agent.runtime.*;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Set;

/** Agent/DAG 的只读记忆能力；项目和任务范围由 Runtime 从调用输入注入。 */
@Component
public class MemoryCapability implements CapabilityInvoker {
    private final CapabilityRegistry registry;
    private final MemoryService memory;
    private final ObjectMapper mapper;
    /** 装配能力注册、记忆查询和工具结果序列化所需的依赖。 */
    public MemoryCapability(CapabilityRegistry registry,MemoryService memory,ObjectMapper mapper) {
        this.registry=registry;this.memory=memory;this.mapper=mapper;
    }
    /** 注册只读记忆召回工具，模型参数只暴露问题字段。 */
    @PostConstruct
    public void register() {
        var specification=ToolSpecification.builder().name("memory__recall")
                .description("召回用户明确记录的长期记忆及当前输入授权的项目/任务记忆。范围由服务端限定。")
                .parameters(JsonObjectSchema.builder().addStringProperty("question").required("question").build()).build();
        try {
            registry.register(new CapabilityDescriptor("memory.recall",CapabilityType.MEMORY,"记忆召回",
                    specification.description(),mapper.readTree(specification.toJson()).path("parameters"),null,
                    specification.name(),true,false,"memory","recall",Map.of(),specification));
        } catch(Exception error){throw new IllegalStateException("记忆能力注册失败",error);}
    }
    /** 声明此调用器负责记忆能力。 */
    @Override public Set<CapabilityType> supportedTypes(){return Set.of(CapabilityType.MEMORY);}
    /** 使用调用参数中经过 Runtime 绑定的项目和任务范围召回记忆。 */
    @Override public CapabilityInvocation.Result invoke(CapabilityInvocation.Request request) {
        long start=System.nanoTime();var args=request.arguments();
        var result=memory.recall(args.path("question").asText(),args.path("projectId").asText(null),args.path("taskId").asText(null),8);
        return new CapabilityInvocation.Result(true,mapper.valueToTree(result),null,null,false,(System.nanoTime()-start)/1000000);
    }
}
