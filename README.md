# LangChain4j Chat Agent

这是一个基于 Spring Boot 3 和 LangChain4j 的 Web 智能助手，支持流式聊天、模型配置切换、文档问答，以及 GitHub、网页抓取和受限文件系统工具。

系统的非商用目标架构、数据流、用例以及 Capability/Tools 关系见 [非商用 Agent 平台详细架构设计](docs/architecture/non-commercial-agent-platform-architecture.md)。

## 运行环境

- JDK 21 或更高版本
- Maven 3.8 或更高版本
- Ollama：仅在显式选择本地聊天模型时需要；默认向量生成已改用硅基流动
- Redis：上传文档并进行问答时需要
- MinIO：上传文件和文档问答时需要，文件内容不再保存到应用本地目录
- Node.js：使用 GitHub 工具时需要，项目会通过 `npx` 启动 MCP 服务
- Python `uvx`：使用网页抓取工具时需要

默认 Embedding 使用硅基流动 `BAAI/bge-m3`，重排使用 `BAAI/bge-reranker-v2-m3`，两者默认读取 `GJLD_EMBEDDING` 环境变量。文档索引不再依赖本地 Ollama。

## 快速启动

### 1. 准备向量模型与启动项目

设置硅基流动密钥（不要提交实际密钥），并确保 MySQL、Redis、MinIO 与 Qdrant 已启动：

```powershell
$env:GJLD_EMBEDDING = "你的硅基流动API Key"
```

默认 Embedding 提供方为 `siliconflow`。聊天模型仍由 `LLM_PROVIDER` 独立选择，启动项目：

```powershell
mvn -pl openmanus-app -am package -DskipTests
java -jar openmanus-app/target/openmanus-app-0.0.1-SNAPSHOT.jar
```

也可以在 IntelliJ IDEA 中运行 `com.melon.OpenManusApplication`。

### 2. 使用云端模型

PowerShell 示例：

```powershell
$env:LLM_PROVIDER = "openai"
$env:OPENAI_API_KEY = "你的 API Key"
$env:OPENAI_MODEL_NAME = "gpt-4o-mini"
mvn -pl openmanus-app -am package -DskipTests
java -jar openmanus-app/target/openmanus-app-0.0.1-SNAPSHOT.jar
```

智谱模型：

```powershell
$env:LLM_PROVIDER = "zhipu"
$env:ZHIPU_API_KEY = "你的 API Key"
$env:ZHIPU_MODEL_NAME = "glm-4"
mvn -pl openmanus-app -am package -DskipTests
java -jar openmanus-app/target/openmanus-app-0.0.1-SNAPSHOT.jar
```

任意兼容 OpenAI Chat Completions 协议的服务：

```powershell
$env:LLM_PROVIDER = "aihub"
$env:AIHUB_BASE_URL = "https://你的服务地址/v1"
$env:AIHUB_API_KEY = "你的 API Key"
$env:AIHUB_MODEL_NAME = "模型名称"
mvn -pl openmanus-app -am package -DskipTests
java -jar openmanus-app/target/openmanus-app-0.0.1-SNAPSHOT.jar
```

也可以直接修改 `openmanus-app/src/main/resources/application.yml` 中的 `langchain4j.chat.provider`。可选值为 `ollama`、`openai`、`zhipu`、`aihub`；切换后重启应用即可，无需修改 Java 代码。

## 使用方式

启动成功后访问 [http://localhost:8898](http://localhost:8898)，在输入框中发送问题。回答通过 SSE 流式显示。

- 普通聊天：直接输入问题。
- 文档问答：上传 PDF、TXT、Markdown、HTML、Word、PPT 或 Excel 文件后再提问，单文件最大 10 MB、一次最多 3 个文件。
- GitHub 查询：先设置 `GITHUB_TOKEN`，再询问仓库、提交或 Issue 等信息。
- 网页分析：安装 `uvx` 后，在问题中包含要分析的网址。
- 文件操作：默认只允许读取项目下的 `data` 目录，写入和删除默认关闭。

上传文件使用 MinIO 对象存储，默认配置可通过环境变量覆盖：

```powershell
$env:MINIO_ENDPOINT = "http://192.168.101.67:9000"
$env:MINIO_ACCESS_KEY = "minioadmin"
$env:MINIO_SECRET_KEY = "minioadmin"
$env:MINIO_BUCKET = "la4j-agent"
```

应用会在首次文件操作时检查并按需创建 Bucket。Redis 仅保存文件 ID、Object Key 等元数据；RAG 或 Docling 解析时会临时下载对象，处理完成后自动清理本地副本。

## 短文颠覆性改编工作流

项目提供了一套异步、多模型接力的短文改编工作流：

```text
原文分析 → 三角色头脑风暴 → 方向吸引力/网络热度评估 → 等待用户选择
→ MCP 网络研究 → 多篇候选稿 → 对抗性评审 → 终稿编辑 → 质量审核/重写 → Redis 落库
```

也可以直接打开对话页面，在左侧 `Agent Tools` 中选择“颠覆性改编”，粘贴短文后发送。头脑风暴完成后，页面会按综合分展示多个方向及其阅读吸引力、当前网络热度和评分理由；用户选择后，工作流才继续研究、写作与审核。

启动前需要可用的 Redis 和聊天模型。网络研究默认复用现有 Fetch MCP，因此需要安装 `uvx`；如果请求设置 `researchEnabled: false`，则跳过网络研究。

创建工作流：

```powershell
$body = @{
  title = "雨夜"
  sourceText = "这里填写需要改编的短文……"
  targetAudience = "成年文学读者"
  constraints = @("保留雨伞意象", "控制在1500字以内")
  researchEnabled = $true
  outputStyle = "LITERARY_PROSE"
} | ConvertTo-Json

$result = Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:8898/api/rewrite-workflows" `
  -ContentType "application/json" `
  -Body $body

$result
```

接口返回 HTTP `202 Accepted` 和 `workflowId`。查询执行状态及最终结果：

```powershell
Invoke-RestMethod "http://localhost:8898/api/rewrite-workflows/$($result.workflowId)"
```

当状态进入 `AWAITING_DIRECTION_SELECTION` 后，从返回的 `directions` 中选择 1 到 `maxDirectionSelections` 个方向并继续：

```powershell
$selection = @{
  directionIds = @("structure-1", "style-2")
} | ConvertTo-Json

Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:8898/api/rewrite-workflows/$($result.workflowId)/directions" `
  -ContentType "application/json" `
  -Body $selection
```

最终状态包括：

- `AWAITING_DIRECTION_SELECTION`：方向评估完成，等待用户提交选择；
- `COMPLETED`：终稿达到质量门槛；
- `NEEDS_REVIEW`：达到最大重写次数但仍需要人工审核；
- `FAILED`：模型、MCP 或 Redis 节点失败；
- `events`：全过程节点轨迹；
- `directions`：包含 `appealScore`、`networkHeatScore`、`networkHeatAvailable` 和综合 `score`；
- `directionTrendEvidence`、`researchEvidence`、`candidates`、`reviews`、`finalDraft`：各阶段结构化产物。

`outputStyle` 支持以下值：

| 值 | 页面名称 | 约束效果 |
|---|---|---|
| `LITERARY_PROSE` | 文学散文 | 连续文学段落，禁止档案、公文、报告和剧本格式 |
| `PRESERVE_ORIGINAL_GENRE` | 保持原文体裁 | 保持分析节点识别出的原始体裁 |
| `EXPERIMENTAL` | 文体实验 | 允许明显的结构与文体实验 |
| `FICTION` | 小说化改编 | 使用场景、行动、对话和完整叙事弧线 |
| `DRAMA_SCRIPT` | 戏剧/剧本 | 完整分镜头剧本；使用配置的生图模型为每段分镜生成一张图 |
| `DOSSIER_EPISTOLARY` | 档案或书信体 | 使用档案、报告、书信或日记推进叙事 |

工作流会把输出风格作为硬约束传递给头脑风暴、方向评估、候选稿、终稿和质量审核。终稿只有在综合分与 `genreComplianceScore` 均达到质量阈值时才会进入 `COMPLETED`。

戏剧/剧本的终稿包含场景、内外景、景别、画面内容、道具服装、构图、对白/人声、音乐音效、摄法、时长、特效与转场。`storyboardShots` 按剧情顺序保存每段分镜的 `number`、`title`、`script`、`imagePrompt`、`imageUrl`、`imageStatus` 和 `imageError`，页面逐段展示剧本及图片。出图仅在质量审核和重写全部结束后执行。

分镜出图优先使用独立的 `langchain4j.chat.image` 配置，与聊天角色的模型选择分开。当前项目默认启用 `codex/gpt-image-2`，通过 OpenAI 兼容的 `/images/generations` 接口调用。未设置 `IMAGE_API_KEY` 和 `IMAGE_BASE_URL` 时沿用配置文件中已有的网关凭据与地址回退：

```yaml
langchain4j:
  chat:
    image:
      enabled: ${IMAGE_GENERATION_ENABLED:true}
      api-key: ${IMAGE_API_KEY:${LINSHICHE_API_KEY:cc-switch}}
      model-name: ${IMAGE_MODEL_NAME:codex/gpt-image-2}
      base-url: ${IMAGE_BASE_URL:${ZHIPU_BASE_URL:http://127.0.0.1:3425/v1}}
      timeout: ${IMAGE_TIMEOUT:600s}
```

选择“颠覆性改编 → 戏剧/剧本”后，终稿审核与重写结束才开始出图，每段分镜单独调用一次并保存结果。页面显示每段剧本、对应图片及出图进度；单张失败不会中止其余分镜。模型 API 返回的 Base64 图片或 HTTP/HTTPS 图片地址均可展示。

关闭独立生图配置（`IMAGE_GENERATION_ENABLED=false`）后，仍兼容既有的角色供应商文生图配置。例如，终稿使用 `openai` 供应商时：

```yaml
langchain4j:
  chat:
    openai:
      image-generation:
        enabled: true
        model-name: ${OPENAI_IMAGE_MODEL} # 填写当前服务实际支持的文生图模型
        # base-url: https://your-service.example/v1 # 留空复用聊天供应商地址
        timeout: 300s
rewrite-workflow:
  provider-by-role:
    FINAL_EDITOR: openai
```

兼容配置支持 `openai`、`zhipu`、`aihub` 的图片生成接口，复用对应供应商 API Key。使用命名 `ChatModel` Bean 时，通过 `rewrite-workflow.image-model-by-chat-model: {自定义聊天Bean名: 自定义ImageModelBean名}` 显式关联图片模型；未关联时不会回退到其他供应商。独立模型和兼容路由均未配置时，分镜标记为 `SKIPPED` 并保留文字。单张失败标记为 `FAILED`，其余分镜继续出图，成功图片和剧本均保留，最终状态为 `NEEDS_REVIEW`。

可用以下命令单独验证工作流出图链路：

```shell
mvn -pl openmanus-workflow -am -Drewrite.image.live-test=true -Dtest=StoryboardWorkflowTest#generatesActualImagesForBothShotsUsingTheProjectConfiguration -Dsurefire.failIfNoSpecifiedTests=false test
```

此测试读取项目配置并实际生成两张分镜图，将图片和工作流结果 JSON 保存到 `data/image-probe/workflow`。分析、写稿和存储使用测试替身；普通测试默认跳过真实模型调用。

### 为不同角色配置不同模型

默认所有角色使用 `langchain4j.chat.provider` 指定的主模型。可以将角色直接映射到 `ollama`、`openai`、`zhipu` 或 `aihub`：

```yaml
rewrite-workflow:
  provider-by-role:
    ANALYZER: aihub
    BRAINSTORM_STRUCTURE: openai
    BRAINSTORM_STYLE: openai
    BRAINSTORM_WORLD: ollama
    CREATIVE_DIRECTOR: aihub
    RESEARCH_PLANNER: aihub
    DRAFT_WRITER: openai
    CRITIC: aihub
    FINAL_EDITOR: openai
    QUALITY_REVIEWER: aihub
```

对应 provider 的地址、模型名和 API Key 仍在 `langchain4j.chat` 下配置。也可以通过 `model-by-role` 指向自定义的 `ChatModel` Spring Bean；`model-by-role` 的优先级高于 `provider-by-role`。

### 接入 MCP 搜索服务

默认 Fetch MCP 只能读取研究策划节点给出的明确 URL。如需真正的搜索能力，可以接入任意 stdio MCP 搜索服务：

```yaml
rewrite-workflow:
  research:
    search-enabled: true
    search-command:
      - npx
      - -y
      - your-mcp-search-server
    environment:
      SEARCH_API_KEY: ${SEARCH_API_KEY:}
    timeout: 120s
```

工作流只向 MCP 子进程传递配置的环境变量，不允许模型生成命令。MCP 搜索失败时，如果研究任务包含明确 URL，会自动回退到 Fetch MCP。

方向热度必须来自实时 MCP 搜索证据。未启用搜索、搜索调用失败或用户关闭网络研究时，页面明确显示“无实时数据”，`networkHeatScore` 为 0，综合分仅按阅读吸引力排序，不会让模型臆测网络热度。

## 轻量 DAG 工作流 MVP

项目包含一套初步通用 DAG 运行内核，支持：

- `START`、`LLM`、`AGENT`、`MCP_TOOL`、`CONDITION`、`END` 节点；
- 使用 `$.nodes.<nodeId>.output...` 引用前序节点输出；
- 在边上配置 `EQ`、`NE`、数值比较、`CONTAINS`、`EXISTS`、`TRUTHY`、`FALSY` 条件；
- `ANY` 和 `ALL` 两种入边合并语义；
- 工作流输入、节点输入和节点输出的基础 JSON Schema 校验；
- 节点超时、有限重试、运行事件以及 Redis 状态保存；
- LLM 节点通过 `role` 复用 `rewrite-workflow.model-by-role/provider-by-role` 模型路由；
- MCP 节点当前支持 `operation: research`，复用现有 MCP 搜索/网页研究能力。

启动应用后访问 [http://localhost:8898/workflows](http://localhost:8898/workflows) 可以使用第一版可视化设计器。当前支持节点拖放、SVG 连线、节点与条件边配置、上游变量选择、工作流草稿保存、JSON 导入导出、结构化校验、运行历史，以及在画布上查看节点执行状态、输入、输出和错误。

先校验示例定义：

```powershell
$payload = Get-Content -Raw "examples/dag-workflow-run-example.json" | ConvertFrom-Json
$definition = $payload.definition | ConvertTo-Json -Depth 30
Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:8898/api/dag-workflows/validate" `
  -ContentType "application/json" `
  -Body $definition
```

启动运行并查询结果：

```powershell
$body = Get-Content -Raw "examples/dag-workflow-run-example.json"
$run = Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:8898/api/dag-workflows/runs" `
  -ContentType "application/json" `
  -Body $body

Invoke-RestMethod "http://localhost:8898/api/dag-workflows/runs/$($run.runId)"
```

这是执行内核和画布的第一阶段：工作流定义及画布位置可以保存到 Redis，运行状态仍以 Redis 单 Key 保存，调度使用本地线程池。完整版本发布与回滚、持久消息队列、人工节点、动态 `FOREACH/JOIN`、暂停恢复、单节点执行和并行分支调度尚未包含在该 MVP 中。

### 聊天与 DAG 复用通用 Agent

普通会话聊天 `/api/memory/chat` 和无会话聊天 `/api/ai/stream` 都运行 `general-chat-agent`。在工作流设计器的 AGENT 节点中，将「Agent 来源」切换为「通用聊天 Agent」，并映射 `objective`，即可复用同一套提示词、模型和能力选择。API 使用 `config.agentRef` 指定该 Agent，已有内联 AGENT 节点继续使用原有配置。

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/dag-workflows/runs `
  -ContentType 'application/json; charset=utf-8' -InFile examples/dag-general-chat-agent-run-example.json
```

通过返回的 `runId` 查询 `GET /api/dag-workflows/runs/{runId}`，节点输出包含 `agentId`、`finalAnswer` 和执行步骤。

## Agent 平台内核

项目包含三个相互独立但可以组合使用的核心组件：

- Agent Runtime：执行“模型决策、能力调用、观察结果、继续或结束”的多轮循环。
- Capability Registry：保存 Agent 可使用的能力、模型工具名、输入 Schema 和提供方信息。
- MCP Invocation Service：管理 MCP 长连接、发现 Tools、校验参数并执行调用。

ReAct 的执行限制由 `agent-platform.runtime` 配置。`default-max-steps` 和 Agent 定义中的 `maxSteps` 继续限制普通模型轮次，行动停止后可以额外执行一次无工具收束。Runtime 的总时长从 `AgentRuntime.execute()` 开始计算。

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `max-tool-calls` | 16 | 一次运行的工具提交尝试上限 |
| `max-tool-calls-per-round` | 4 | 一条模型消息允许执行的工具请求数 |
| `max-repeated-tool-calls` | 2 | 相同参数连续取得相同结果的允许次数，下一次调用触发收束 |
| `max-run-duration` | 600s | 包含最终收束的 Runtime 总时间 |
| `max-model-duration` | 120s | 单次模型调用等待上限 |
| `max-tool-duration` | 120s | 单次工具调用等待上限 |
| `finalization-duration` | 30s | 从总时间中预留给额外一次收束的时间 |
| `max-concurrent-invocations` | 16 | 每个 Runtime 同时执行的外部调用数，另有一个排队位置 |

所有限制均需为正数，`finalization-duration` 必须小于 `max-run-duration`。每次工具请求都有对应的结果消息，超出预算的请求记录拒绝原因。收束请求携带已配对的工具结果并移除工具规格，收束响应再次请求工具时执行失败。相同参数取得新的结果会重新开始重复计数。轨迹中的 `toolCallId` 关联模型请求、能力调用和 MCP 请求，`round` 标识所属模型轮次。

`AgentRun` 新增 `finishReason`、`modelCallCount` 和 `toolCallCount`。自然回答使用 `FINAL_ANSWER`，预算或重复调用触发收束后保留触发原因。收束产生有效文本时状态为 `COMPLETED`，收束指令要求说明已有结果及未完成事项。硬超时、收束失败或调用池拒绝返回失败状态，中断返回 `CANCELLED`。超时只结束本地等待并请求中断，远端操作仍需按工具能力确认执行状态。次数统计以 Runtime 提交尝试为准，SDK 内部重试也受外层等待时间限制。

配置一个 stdio MCP Server：

```yaml
agent-platform:
  mcp:
    servers:
      web-fetch:
        enabled: true
        transport: STDIO
        command: ["uvx", "mcp-server-fetch"]
        timeout: 120s
```

也可以配置 Streamable HTTP：

```yaml
agent-platform:
  mcp:
    servers:
      remote-tools:
        enabled: true
        transport: STREAMABLE_HTTP
        url: https://example.com/mcp
        headers:
          Authorization: ${REMOTE_MCP_AUTHORIZATION:}
```

服务启动后，先发现并注册 MCP Tools：

```text
POST /api/agent-platform/mcp/servers/web-fetch/refresh
GET  /api/agent-platform/capabilities
```

然后运行 Agent：

```text
POST /api/agent-platform/runs
Content-Type: application/json
```

请求示例见 `examples/agent-run-example.json`。Agent 只能看到 `capabilityIds` 明确授权的能力，达到 `maxSteps` 后会自动停止。DAG 工作流也已支持 `AGENT` 节点，节点通过 `config.systemPrompt`、`config.capabilityIds` 和输入中的 `objective` 启动同一个 Agent Runtime。

现有功能已统一接入这套内核：

- 普通聊天直接进入 Agent Runtime，由模型在已授权能力中自主选择，不再先调用模型进行工具分类。
- GitHub、Fetch 和文章改编研究不再各自创建 MCP Client，统一复用 MCP Connection Manager。
- `tool-security.github`、`tool-security.fetch` 和 `rewrite-workflow.research` 会自动映射为平台 MCP Server，原配置继续有效。
- 文件系统只读操作注册为 `LOCAL_TOOL` Capability；写入和删除不会自动暴露给通用 Agent。
- 上传文档检索注册为 `KNOWLEDGE` Capability，并按照本次请求的 `fileIds` 隔离检索范围。
- DAG 的 `MCP_TOOL` 节点可通过 `config.capabilityId` 调用任意已注册 MCP Tool；原来的 `operation: research` 仍兼容。

普通聊天入口仍为 `POST /api/ai/stream`，但其内部已经统一走 Agent Runtime。返回仍采用 SSE，当前 Agent 完成后一次性发送最终答案；逐 Token 与逐 Step 事件流将在后续运行事件层实现。

## 启用文档问答

文档信息临时保存在 Redis 中，向量由 Ollama 嵌入模型生成。先启动 Redis，再运行项目：

```powershell
redis-server
```

如果 Redis 不在本机，可设置：

```powershell
$env:REDIS_HOST = "redis.example.com"
$env:REDIS_PORT = "6379"
$env:REDIS_PASSWORD = "密码"
```

当前默认通过 OpenAI 兼容 Embedding 协议调用硅基流动，通过 `SILICONFLOW_EMBEDDING_MODEL` 指定向量模型，密钥读取 `GJLD_EMBEDDING`。`BAAI/bge-reranker-v2-m3` 用于重排，不能配置为向量模型。本地 Ollama 适配器仅保留显式配置能力，不会自动回退使用。

项目会优先使用 Docling 将复杂文档转换为 Markdown，再交给 LangChain4j 切片和向量化。Docling 适合 PDF、Word、PPT、Excel、HTML 等结构复杂的文档，能更好保留标题、表格和阅读顺序。

安装 Docling：

```powershell
python -m venv .venv-docling
.\.venv-docling\Scripts\python.exe -m pip install --upgrade pip
.\.venv-docling\Scripts\python.exe -m pip install docling
.\.venv-docling\Scripts\docling.exe --help
```

默认配置会优先调用项目内的 `.venv-docling/Scripts/docling.exe`。如果你使用全局安装的 Docling，可以通过 `DOCLING_COMMAND` 覆盖。

如果本机没有安装 Docling，项目会自动回退到原生解析器；原生解析器目前只支持 PDF 和 TXT。也可以显式关闭 Docling：

```powershell
$env:RAG_DOCLING_ENABLED = "false"
```

工具安全限制统一配置在 `tool-security` 下。常用环境变量如下：

```powershell
$env:GITHUB_TOOL_ENABLED = "true"
$env:GITHUB_ALLOWED_REPOSITORIES = "langchain4j/langchain4j,vecter/LangChain4j-Springboot"
$env:FETCH_ALLOWED_DOMAINS = "docs.langchain4j.dev,github.com"
$env:FETCH_MAX_RESPONSE_LENGTH = "200000"
$env:FILESYSTEM_ALLOWED_ROOTS = "D:\safe-directory"
$env:FILESYSTEM_ALLOW_WRITE = "false"
$env:FILESYSTEM_ALLOW_DELETE = "false"
$env:FILESYSTEM_MAX_READ_BYTES = "1048576"
$env:DOCLING_OCR_ENABLED = "true"
$env:DOCLING_OCR_ENGINE = "rapidocr"
$env:DOCLING_OCR_LANG = "ch"
```

RAG 切片参数可以通过环境变量调整：

```powershell
$env:RAG_SPLITTER_MAX_SEGMENT_SIZE = "800"
$env:RAG_SPLITTER_MAX_OVERLAP_SIZE = "120"
$env:RAG_RETRIEVER_MAX_RESULTS = "6"
$env:RAG_RETRIEVER_MIN_SCORE = "0.55"
```

### Qdrant 向量存储

RAG 默认使用 Qdrant 持久化文档向量，Java 客户端连接的是 gRPC 端口 `6334`。本地开发可使用项目提供的 Compose 文件：

Windows 安装并启动 Docker Desktop（Linux 容器模式）后，可在项目根目录运行 `./start-qdrant.cmd`，或执行 `powershell -NoProfile -ExecutionPolicy Bypass -File .\Start-Qdrant.ps1`。脚本复用下方 Compose 配置和现有命名卷，不重建已有容器，并等待 `/readyz` 就绪；启动完成后打开 `http://localhost:6333/dashboard`。

```powershell
docker compose -f compose.qdrant.yml up -d
```

启动后可通过 `http://localhost:6333/dashboard` 查看 Collection。常用连接参数：

```powershell
$env:QDRANT_HOST = "localhost"
$env:QDRANT_GRPC_PORT = "6334"
$env:QDRANT_COLLECTION = "la4j_agent_sf_bge_m3"
$env:QDRANT_DIMENSION = "1024"
$env:QDRANT_DISTANCE = "COSINE"
$env:QDRANT_USE_TLS = "false"
$env:QDRANT_API_KEY = ""
```

`QDRANT_DIMENSION` 必须与 Embedding 模型输出维度一致。当前硅基流动 `BAAI/bge-m3` 实测为 1024 维；使用独立 Collection 与旧 Qwen 向量隔离，更换模型后已有文档需重建索引。需要在没有 Qdrant 的测试环境运行时，可以临时设置：

```powershell
$env:RAG_VECTOR_STORE_PROVIDER = "in-memory"
```

Qdrant 启动后，可运行真实 gRPC 联调，验证写入、客户端重连、文件隔离检索及删除；测试结束会清理本次创建的 Point：

```powershell
mvn -pl openmanus-context -am "-Dtest=QdrantIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dqdrant.integration=true" test
```

## Memory 会话与记忆

功能归属、模块间数据边界、已修复串用问题及剩余限制，见 [数据隔离复核](docs/data-isolation-audit.md)。

聊天页已支持基于 MySQL 的会话历史、任务进展和项目绑定，以及基于 MySQL + Qdrant 的长期、项目、任务记忆。侧边栏可切换会话、创建项目/任务、明确记录和修改记忆；普通聊天与知识库问答会自动组装历史上下文。Agent / DAG 可通过 `memory.recall` 读取授权范围内的记忆。

长期写入目前由用户在记忆面板明确提交；自动画像、Skill 学习和 DAG 检查点恢复仍未实现。详见 [Memory 第一版说明](docs/memory-v1.md)。

## MySQL 多数据源

Knowledge 第一版已支持永久知识库、异步文档索引、文档/分块/Embedding 版本、混合检索和重排。管理页位于 `/knowledge`，对话页可选择知识库。详见 [Knowledge 第一版说明](docs/knowledge-v1.md)。

项目使用 Spring JDBC、MySQL Connector/J、HikariCP 和 dynamic-datasource 的 Spring Boot 3 Starter，直接读取 `application.yml` 中的 `spring.datasource.dynamic` 配置。目前配置两个数据源：`szzh_bbzjcx`、`szc_frjzk`，默认数据源为 `szc_frjzk`。已开启严格匹配，错误的数据源名会报错。

业务 Repository 可直接注入 `JdbcTemplate`；不指定数据源时使用默认库，需要切库时在 Spring Bean 的公开方法上增加：

```java
@DS("szzh_bbzjcx")
public String readDatabaseName() {
    return jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
}
```

`@DS` 方法需要通过注入的 Spring Bean 调用，同类内部调用不会经过切库代理。切换数据源应在开启事务前完成；普通 JDBC 本地事务不会跨两个库提供原子提交。

`MySqlConnectionService` 提供只读连接诊断：`currentDatabase()` 检查默认库，`database("szzh_bbzjcx")` 检查指定库。该服务未开放为 HTTP 接口或模型工具。

使用配置中的真实连接执行只读联调：

```powershell
mvn -pl openmanus-app -am "-Dtest=MySqlIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmysql.integration=true" test
```

Knowledge 的元数据与版本保存在 MySQL 的 `la4j_kb_` 前缀表；临时附件和工作流仍使用现有 Redis 持久化，原文件保存在 MinIO，向量保存在 Qdrant。

## 启用扩展工具

GitHub 工具：

```powershell
$env:GITHUB_TOKEN = "你的 GitHub Token"
node --version
npx --version
```

网页抓取工具：

```powershell
uvx --version
```

文件系统工具：

```powershell
$env:FILESYSTEM_ALLOWED_ROOTS = "D:\safe-directory"
$env:FILESYSTEM_ALLOW_WRITE = "false"
$env:FILESYSTEM_ALLOW_DELETE = "false"
```

多个允许目录使用英文逗号分隔。只有明确需要时才应开启写入或删除权限。

## 对话媒体返回约定

- 图片能力返回 Markdown 图片：`![图片说明](图片地址)`，对话页会直接显示并提供原图入口。
- 视频能力返回 Markdown 链接：`[下载视频](视频地址)`，对话页只显示下载卡片，不自动播放视频。
- MinIO 文件可通过 `/api/ai/upload/{fileId}/content` 读取；安全的栅格图片使用内联响应，视频及其他文件使用附件下载响应。

上述约定负责媒体传输和页面呈现。实际生成图片或视频仍需配置对应模型，并在 Capability Registry 中注册生成能力。

## Maven 模块

| 模块 | 职责 |
| --- | --- |
| `openmanus-runtime` | Agent 执行内核、能力目录和调用契约 |
| `openmanus-context` | Memory、Knowledge 和临时附件 RAG |
| `openmanus-tools` | MCP 发现与调用、本地工具及兼容入口 |
| `openmanus-workflow` | DAG 和改编工作流 |
| `openmanus-model` | Chat、Streaming Chat 和 Embedding 模型配置 |
| `openmanus-app` | Spring Boot 装配、HTTP、SSE 和页面 |

## 构建可执行包

```powershell
mvn clean package
java -jar openmanus-app/target/openmanus-app-0.0.1-SNAPSHOT.jar
```

## 常见问题

- 页面能打开但模型无回复：确认所选模型服务已启动、API Key 有效，且 `base-url` 使用 OpenAI 兼容地址而不是 Anthropic 专用地址。
- 改编工作流出现 `WorkflowModelGateway.text/json` 的 `request timed out`：表示模型 HTTP 请求超时，排查报错中的角色、模型路由和耗时。聊天模型的 HTTP 超时默认均为 `600s`，可通过 `ZHIPU_TIMEOUT`、`AIHUB_TIMEOUT`、`OPENAI_TIMEOUT`、`OLLAMA_CHAT_TIMEOUT` 分别覆盖，修改后重启应用。SDK 自身可能重试，因此总耗时可能超过单次 HTTP 超时；远端服务或代理也可能提前中断请求。
- 文档上传失败：确认 Redis 已启动；超过 Spring 上传限制的文件会被拒绝。
- 文档无法回答：确认 Ollama 已启动并已下载嵌入模型；复杂文档建议安装 Docling，否则只能回退解析 PDF 和 TXT。
- GitHub 查询失败：确认 Node.js、`npx` 和 `GITHUB_TOKEN` 可用。
- 网页抓取失败：确认已安装 Python 环境和 `uvx`，并且目标网站可访问。
- 端口冲突：通过 `SERVER_PORT` 覆盖 Spring Boot 默认端口，例如 `$env:SERVER_PORT = "8081"`。

主要配置位于 `openmanus-app/src/main/resources/application.yml`。API Key 和访问令牌均通过环境变量注入，请勿将真实凭据提交到仓库。

改编角色先匹配 `rewrite-workflow.model-by-role` 的自定义模型 Bean，再匹配 `provider-by-role`，最后使用默认 `chatLanguageModel`（`LLM_PROVIDER` 默认 `zhipu`）。自定义 Bean 的超时由它自身配置。直接改编模型调用使用供应商 HTTP 超时；聊天 Agent 另受 ReAct 的 `agent.runtime.max-model-duration` 限制（默认 `120s`），DAG 节点另受 `timeoutSeconds` 限制（默认 `120`），需要结合实际入口调整。
