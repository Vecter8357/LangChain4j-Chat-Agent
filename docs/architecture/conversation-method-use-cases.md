# 对话方法用例图

依据当前六模块代码梳理，节点标注实际类和方法。实线表示调用或执行分支，虚线表示结果返回或依赖装配。图聚焦会话聊天、无会话聊天、选库问答，以及 DAG 复用通用 Agent 的成功路径。

```mermaid
flowchart TB
    USER([用户])

    subgraph APP[openmanus-app · 对话入口]
        UI["chat.html 发送问题<br/>memoryUI.ensureConversation()"]
        MC["MemoryController.chat()<br/>POST /api/memory/chat"]
        AI["AIController.askStream()<br/>POST /api/ai/stream"]
        STREAM["StreamingChatServiceImpl.processAgentRequest()"]
        KC["KnowledgeController.chat()<br/>POST /api/knowledge/chat"]
        PG["PlatformAgentService.runGeneral()<br/>筛选本轮能力 → execute()"]
        PD["PlatformAgentService.runGeneralForWorkflow()"]
        SSE["SseEmitter.send() / complete()<br/>由对应 Controller 或 emit() 返回答案"]
    end

    subgraph CONTEXT[openmanus-context · 会话与知识]
        MEM["MemoryChatService.chat()"]
        PRE["MemoryRepository.claim() / conversation()<br/>messages() / summary() / taskResult()<br/>MemoryService.recall()<br/>租约、历史、任务结果和记忆上下文"]
        MODE{"knowledgeBaseIds 非空？"}
        CALLBACK["MemoryChatService.AgentAnswer.run()"]
        KB["KnowledgeChatService.answer()"]
        SEARCH["KnowledgeSearchService.search() / context()<br/>全文与向量召回 → RRF → 重排 → 来源校验<br/>有附件时调用 RagService.retrieveContext()"]
        KMODEL["ChatModel.chat()<br/>使用本轮证据和最近用户问题生成回答"]
        KRESULT["KnowledgeChatService.answer()<br/>追加检索来源与 warnings"]
        SAVE["MemoryRepository.complete()<br/>事务保存问答和任务最近结果<br/>finally 中 release()"]
        KI["KnowledgeCapabilityInvoker.invoke()<br/>answer → RagService.retrieveContext()<br/>search → KnowledgeSearchService.search()"]
        MI["MemoryCapability.invoke()<br/>MemoryService.recall()"]
    end

    subgraph RUNTIME[openmanus-runtime · Agent 执行]
        EXEC["DefaultAgentRuntime.execute()<br/>resolveCapabilities() / 组装消息"]
        MODEL["ChatModel.chat()<br/>模型生成答案或工具请求"]
        TOOL{"包含 ToolExecutionRequest？"}
        INV["DefaultAgentRuntime.invokeCapability()<br/>parseArguments() / 范围约束<br/>JsonSchemaValidator.validate()"]
        DIS["CapabilityInvokerRegistry.require()<br/>CapabilityInvoker.invoke()"]
        OBS["ToolExecutionResultMessage.from()<br/>记录 AgentStep 并进入下一轮模型调用"]
        RESULT["AgentRun<br/>finalAnswer / steps / status"]
    end

    subgraph TOOLS[openmanus-tools · 能力发现与执行]
        PROV["CapabilityProvisioningService.ensureAllEnabledMcpProviders()<br/>按需 refreshCapabilities()"]
        MCP["McpCapabilityInvoker.invoke()<br/>DefaultMcpInvocationService.invoke()<br/>McpClient.executeTool()"]
        LOCAL["FilesystemCapabilityInvoker.invoke()<br/>FilesystemTool 只读操作"]
    end

    subgraph WORKFLOW[openmanus-workflow · 复用入口]
        DAG["AgentDagNodeExecutor.execute()<br/>agentRef = general-chat-agent<br/>GeneralAgentRunner.run()"]
        DOUT["AgentDagNodeExecutor.completed()<br/>输出 AgentRun 给后续 DAG 节点"]
    end

    subgraph MODELS[openmanus-model · 模型装配]
        CONFIG["LangChain4jChatConfig.chatLanguageModel()<br/>ChatModelFactory.create()"]
    end

    USER --> UI --> MC --> MEM --> PRE --> MODE
    MODE -->|普通聊天| CALLBACK --> PG
    MODE -->|选库问答| KB --> SEARCH
    SEARCH -->|有知识库来源或本轮有附件| KMODEL --> KRESULT
    SEARCH -->|当前来源为空且无临时附件| KRESULT
    KRESULT -.->|会话调用| SAVE
    KRESULT -.->|无会话接口调用| SSE

    AI --> STREAM --> PG
    KC --> KB
    DAG --> PD --> PG
    PG -->|准备可用能力| PROV
    PG -->|组装 AgentRunRequest| EXEC
    EXEC --> MODEL --> TOOL
    TOOL -->|是| INV --> DIS
    DIS -->|MCP_TOOL| MCP
    DIS -->|LOCAL_TOOL| LOCAL
    DIS -->|KNOWLEDGE| KI
    DIS -->|MEMORY| MI
    MCP -.-> OBS
    LOCAL -.-> OBS
    KI -.-> OBS
    MI -.-> OBS
    OBS --> MODEL
    TOOL -->|否，得到最终答案| RESULT
    RESULT -.->|会话调用| SAVE
    RESULT -.->|无会话调用，经 PlatformAgentService.answer()| SSE
    RESULT -.->|DAG 调用| DOUT
    SAVE -.->|Future 完成后 whenComplete()| SSE
    SSE -.-> USER
    CONFIG -.->|注入默认 ChatModel| MODEL
    CONFIG -.->|注入默认 ChatModel| KMODEL
```

聊天页的普通发送会先确保会话存在，再调用 `/api/memory/chat`。首次使用的会话会经 `MemoryController.conversation()` → `MemoryService.createConversation()` → `MemoryRepository.createConversation()` 创建。`MemoryChatService.chat()` 先固定证据范围、领取租约、读取历史并召回记忆，然后根据知识库选择进入普通 Agent 或选库问答。 [聊天页](../../openmanus-app/src/main/resources/templates/chat.html) · [会话前端](../../openmanus-app/src/main/resources/static/js/memory.js) · [会话编排](../../openmanus-context/src/main/java/com/melon/memory/MemoryChatService.java)

普通聊天与 DAG 引用都由 `PlatformAgentService` 构造 `general-chat-agent`。会话入口通过 `OpenManusApplication.memoryAgentAnswer()` 绑定 `runGeneral()`，DAG 入口通过 `generalAgentRunner()` 绑定 `runGeneralForWorkflow()`。DAG 引用路径使用其映射输入，会话路径另有历史和记忆上下文。 [应用装配](../../openmanus-app/src/main/java/com/melon/OpenManusApplication.java) · [通用 Agent 门面](../../openmanus-app/src/main/java/com/melon/agent/runtime/PlatformAgentService.java) · [DAG Agent 节点](../../openmanus-workflow/src/main/java/com/melon/workflow/dag/executor/AgentDagNodeExecutor.java)

选库问答由 `KnowledgeChatService.answer()` 确定性检索后直接调用模型。该方法使用最近三个历史用户问题解释指代，知识事实取自本轮来源，`memoryContext` 参数当前未参与消息构造。通用聊天的能力筛选会排除 `knowledge.search`，有附件时才开放 `knowledge.rag.answer`。图中的 `search` 工具分支适用于显式授予该能力的 Agent，例如配置了能力白名单及知识库范围的内联 DAG Agent。 [知识问答](../../openmanus-context/src/main/java/com/melon/knowledge/KnowledgeChatService.java) · [能力筛选](../../openmanus-app/src/main/java/com/melon/agent/runtime/PlatformAgentService.java)

SSE 当前在模型执行完成后发送最终文本。成功的会话问答经 `MemoryRepository.complete()` 保存用户消息、助手回答及相关任务最近结果，再由 Controller 的完成回调返回。执行开始后的浏览器断开仍允许成功问答落库，排队期间断开可放弃执行。图中的回传分支按原始调用入口择一返回。 [会话返回](../../openmanus-app/src/main/java/com/melon/memory/MemoryController.java) · [会话保存](../../openmanus-context/src/main/java/com/melon/memory/MemoryRepository.java) · [无会话返回](../../openmanus-app/src/main/java/com/melon/service/impl/StreamingChatServiceImpl.java)

| 对话用例 | 关键方法链与约束 |
| --- | --- |
| 普通多轮对话 | `MemoryController.chat()` → `MemoryChatService.chat()` → `AgentAnswer.run()` → `PlatformAgentService.runGeneral()` → `DefaultAgentRuntime.execute()`。同一会话的证据范围固定，完成后事务保存问答 |
| 临时附件问答 | 先经 `AIController.uploadFiles()` → `FileStorageService.storeAll()` 上传，发送时携带 `fileIds`。Runtime 自主调用 `KnowledgeCapabilityInvoker.invoke()` → `RagService.retrieveContext()`，首次需要时在其中执行 `loadFileToRag()` |
| 指定知识库问答 | `MemoryChatService.chat()` → `KnowledgeChatService.answer()` → `KnowledgeSearchService.search()`，可附加临时附件上下文，再由模型生成答案并由服务端追加来源 |
| 无会话普通问答 | `AIController.askStream()` → `StreamingChatServiceImpl.processAgentRequest()` → `emit()`。异步任务执行 `PlatformAgentService.runGeneral()` 和 `answer()` 后发送最终文本 |
| 无会话知识库问答 | `KnowledgeController.chat()` → `KnowledgeChatService.answer()`，接口提供空历史 |
| MCP 工具调用 | `DefaultAgentRuntime.invokeCapability()` → `McpCapabilityInvoker.invoke()` → `DefaultMcpInvocationService.invoke()` → `McpClient.executeTool()`。调用前检查工具 Schema 及 Fetch、GitHub 安全策略 |
| 本地文件读取 | `DefaultAgentRuntime.invokeCapability()` → `FilesystemCapabilityInvoker.invoke()` → `FilesystemTool.listDirectory()`、`readFile()`、`searchFiles()` 或 `getFilesystemInfo()` |
| 模型再次召回记忆 | `DefaultAgentRuntime.invokeCapability()` → `MemoryCapability.invoke()` → `MemoryService.recall()`。Runtime 使用本轮服务端输入覆盖项目和任务参数 |
| DAG 复用对话 Agent | `AgentDagNodeExecutor.execute()` → `GeneralAgentRunner.run()` → `PlatformAgentService.runGeneralForWorkflow()` → 共同的 `runGeneral()`。成功的 AgentRun 由 `completed()` 转为节点输出 |

关键实现入口为 [Runtime 循环](../../openmanus-runtime/src/main/java/com/melon/agent/runtime/DefaultAgentRuntime.java)、[知识能力调用器](../../openmanus-context/src/main/java/com/melon/knowledge/KnowledgeCapabilityInvoker.java)、[记忆能力](../../openmanus-context/src/main/java/com/melon/memory/MemoryCapability.java)、[MCP 调用](../../openmanus-tools/src/main/java/com/melon/mcp/DefaultMcpInvocationService.java) 和 [附件检索](../../openmanus-context/src/main/java/com/melon/service/RagService.java)。

下面是普通多轮对话的方法级时序。前提为会话已存在、`knowledgeBaseIds` 为空，展示最终成功保存并返回答案的路径。`par` 表示 HTTP 线程准备异步响应与聊天线程池任务可交错执行，`whenComplete` 在结果可用且回调已注册后运行。

```mermaid
sequenceDiagram
    autonumber
    actor B as 浏览器
    participant C as MemoryController
    participant S as MemoryChatService
    participant R as MemoryRepository
    participant M as MemoryService
    participant A as PlatformAgentService
    participant P as CapabilityProvisioningService
    participant T as DefaultAgentRuntime
    participant L as ChatModel
    participant I as 能力注册表与调用器

    B->>C: POST /api/memory/chat<br/>conversationId、input、fileIds
    C->>C: chat(input)<br/>创建 SseEmitter 并注册断开回调
    C->>S: chat(input, disconnected)
    S->>S: 校验输入<br/>MemoryEvidenceScope.key(bases, files)
    S->>R: claim(conversationId, evidenceKey)
    Note over R: 事务固定证据范围并领取 90 秒租约
    R-->>S: owner
    S->>R: conversation(conversationId)
    R-->>S: 项目、任务、旧摘要及摘要游标
    S->>S: CompletableFuture.supplyAsync(..., memoryChatExecutor)

    par HTTP 线程准备异步响应
        S-->>C: CompletableFuture
        C->>C: 注册 whenComplete(callback)<br/>返回 SseEmitter
    and memoryChatExecutor 执行问答
        S->>S: 检查连接是否已断开
        S->>R: heartbeat(conversationId, owner)
        R-->>S: 租约有效
        S->>S: TaskScheduler.scheduleAtFixedRate(...)
        Note over S,R: 独立调度线程每 20 秒调用 heartbeat() 续租

        S->>R: messages(conversationId, Long.MAX_VALUE, 100)
        R-->>S: 最近最多 100 条历史，按消息顺序返回
        S->>S: 合并早期消息摘录，最长 6000 字符
        S->>R: summary(conversationId, owner, through, summary)
        S->>S: 选最近 12 条历史<br/>每条最多 4000 字符，总预算 16000 字符

        S->>M: recall(question, projectId, taskId, 6)
        M->>R: requireProject() / task() / entries(scope, scopeId)
        R-->>M: local 长期记忆及当前项目、任务的有效条目
        Note over M: 有候选时执行词项匹配、Embedding 与向量召回<br/>向量服务异常时保留词项结果并生成 warnings
        opt 有参与结果排序的记忆条目
            M->>R: entry(memoryId)
            R-->>M: 当前内容、删除标记和 revision
        end
        M-->>S: Recall，最多 6 条记忆及 warnings
        opt 会话关联任务
            S->>R: task(taskId) / taskResult(taskId, evidenceKey)
            R-->>S: 任务状态、人工进度和同证据范围的最近结果
        end
        S->>S: 组装摘要、任务与记忆上下文<br/>最终限制为 14000 字符

        S->>A: AgentAnswer.run(...) 绑定到 runGeneral(...)<br/>传入历史、记忆上下文与项目和任务范围
        A->>P: ensureAllEnabledMcpProviders()
        opt 提供方尚未完成能力发现
            P->>P: ensureMcpProviders()<br/>McpInvocationService.refreshCapabilities(providerId)
        end
        P-->>A: 当前可用 MCP 能力
        A->>A: 筛选本轮能力，execute() 构造 AgentRunRequest
        A->>T: execute(request)
        T->>T: validate() / resolveCapabilities()
        Note over T: 消息顺序为系统规则、记忆资料 UserMessage<br/>会话历史、当前问题及结构化输入

        loop 普通模型轮次，受 maxSteps 限制，得到答案或触发收束后退出
            T->>L: chat(ChatRequest)<br/>messages 与本轮 ToolSpecification 白名单
            L-->>T: AiMessage
            T->>T: 保存模型消息与 MODEL 类型 AgentStep
            alt 模型请求已授权且可直接执行的工具
                loop 当前 AiMessage 中的每个工具请求
                    T->>T: invokeCapability() / parseArguments()<br/>绑定记忆范围或检查附件范围<br/>JsonSchemaValidator.validate()
                    T->>I: CapabilityInvokerRegistry.require(type)
                    I-->>T: CapabilityInvoker
                    T->>I: invoke(CapabilityInvocation.Request)
                    Note over I: MCP_TOOL 经 McpCapabilityInvoker.invoke()<br/>到 DefaultMcpInvocationService.invoke() 与 McpClient.executeTool()<br/>LOCAL_TOOL 经 FilesystemCapabilityInvoker.invoke()<br/>KNOWLEDGE 经 KnowledgeCapabilityInvoker.invoke() 检索附件
                    opt 模型调用 memory.recall
                        I->>M: MemoryCapability.invoke() → recall(..., 8)
                        M-->>I: 受本轮范围约束的记忆召回结果
                    end
                    I-->>T: CapabilityInvocation.Result
                    T->>T: ToolExecutionResultMessage.from()<br/>加入模型消息并记录 CAPABILITY 类型 AgentStep
                end
                Note over T,L: 下一轮 chat() 携带工具观察结果
            else 模型返回最终文本
                T->>T: 设置 finalAnswer、COMPLETED 和 completedAt
            end
        end
        opt 次数、时间或重复调用触发最终收束
            T->>L: finalizeAnswer() → chat()<br/>已配对的观察结果、收束指令、空工具规格
            L-->>T: 最终文本
            T->>T: 设置 COMPLETED 并保留 finishReason<br/>收束中的新增工具请求会被拒绝
        end
        T-->>A: AgentRun
        A-->>S: AgentRun
        S->>S: 检查 COMPLETED 且 finalAnswer 非空

        S->>R: complete(conversationId, owner, question, answer)
        R->>R: TransactionTemplate.executeWithoutResult(...)
        Note over R: 加行锁确认租约仍有效<br/>成对写入 user 与 assistant 消息<br/>更新会话时间及关联任务的最近结果、证据范围和来源
        R-->>S: 事务提交成功
        S->>S: finally 取消续租任务
        S->>R: release(conversationId, owner)
        R-->>S: 释放租约
        S->>S: Future 正常完成，结果为 answer
    end

    C->>C: whenComplete(answer, null)
    opt SSE 连接仍有效
        C->>C: emitter.send(answer)
        C-->>B: SSE data，最终回答文本
        C->>C: emitter.complete()
        B->>B: 渲染回答并结束本轮等待
    end
```

本轮用户消息和助手回答在 `MemoryRepository.complete()` 中成对提交，提交成功后再触发结果回调和 SSE 发送。生成之前，`summary()` 可能已经更新历史摘录。模型失败、答案为空或保存时租约失效，会经 Future 异常分支返回失败提示。浏览器在执行开始后断开时，后台仍可完成保存，Controller 会跳过向该连接发送结果。 [会话执行顺序](../../openmanus-context/src/main/java/com/melon/memory/MemoryChatService.java) · [事务保存](../../openmanus-context/src/main/java/com/melon/memory/MemoryRepository.java) · [SSE 完成回调](../../openmanus-app/src/main/java/com/melon/memory/MemoryController.java)

第一次 `MemoryService.recall(..., 6)` 由会话服务在模型调用前自动执行。模型运行中还可调用 `memory.recall`，再通过 `MemoryCapability.invoke()` 执行 `recall(..., 8)`。当前自动组装的记忆上下文采用条目正文，召回结果中的 `warnings` 由 Recall 返回，尚未被会话服务拼进该上下文。 [记忆召回](../../openmanus-context/src/main/java/com/melon/memory/MemoryService.java) · [模型记忆能力](../../openmanus-context/src/main/java/com/melon/memory/MemoryCapability.java)
