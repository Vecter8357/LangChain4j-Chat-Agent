# LangChain4j Chat Agent

这是一个基于 Spring Boot 3 和 LangChain4j 的 Web 智能助手，支持流式聊天、模型配置切换、文档问答，以及 GitHub、网页抓取和受限文件系统工具。

系统的非商用目标架构、数据流、用例以及 Capability/Tools 关系见 [非商用 Agent 平台详细架构设计](docs/architecture/non-commercial-agent-platform-architecture.md)。

## 运行环境

- JDK 21 或更高版本
- Maven 3.8 或更高版本
- Ollama：使用本地聊天模型或文档问答时需要
- Redis：上传文档并进行问答时需要
- Node.js：使用 GitHub 工具时需要，项目会通过 `npx` 启动 MCP 服务
- Python `uvx`：使用网页抓取工具时需要

仅使用 OpenAI、智谱或其他 OpenAI 兼容接口进行普通聊天时，不需要启动本地 Ollama；但上传文件仍需要 Ollama 嵌入模型和 Redis。

## 快速启动

### 1. 使用本地 Ollama

先启动 Ollama 并准备模型：

```powershell
ollama pull qwen2.5:7b
ollama pull quentinz/bge-large-zh-v1.5:latest
ollama serve
```

默认配置已经选择 `ollama`，直接启动项目：

```powershell
mvn spring-boot:run
```

也可以在 IntelliJ IDEA 中运行 `com.melon.OpenManusApplication`。

### 2. 使用云端模型

PowerShell 示例：

```powershell
$env:LLM_PROVIDER = "openai"
$env:OPENAI_API_KEY = "你的 API Key"
$env:OPENAI_MODEL_NAME = "gpt-4o-mini"
mvn spring-boot:run
```

智谱模型：

```powershell
$env:LLM_PROVIDER = "zhipu"
$env:ZHIPU_API_KEY = "你的 API Key"
$env:ZHIPU_MODEL_NAME = "glm-4"
mvn spring-boot:run
```

任意兼容 OpenAI Chat Completions 协议的服务：

```powershell
$env:LLM_PROVIDER = "aihub"
$env:AIHUB_BASE_URL = "https://你的服务地址/v1"
$env:AIHUB_API_KEY = "你的 API Key"
$env:AIHUB_MODEL_NAME = "模型名称"
mvn spring-boot:run
```

也可以直接修改 `src/main/resources/application.yml` 中的 `langchain4j.chat.provider`。可选值为 `ollama`、`openai`、`zhipu`、`aihub`；切换后重启应用即可，无需修改 Java 代码。

## 使用方式

启动成功后访问 [http://localhost:8898](http://localhost:8898)，在输入框中发送问题。回答通过 SSE 流式显示。

- 普通聊天：直接输入问题。
- 文档问答：上传 PDF、TXT、Markdown、HTML、Word、PPT 或 Excel 文件后再提问，单文件最大 10 MB、一次最多 3 个文件。
- GitHub 查询：先设置 `GITHUB_TOKEN`，再询问仓库、提交或 Issue 等信息。
- 网页分析：安装 `uvx` 后，在问题中包含要分析的网址。
- 文件操作：默认只允许读取项目下的 `data` 目录，写入和删除默认关闭。

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
| `DRAMA_SCRIPT` | 戏剧/剧本 | 使用场景、角色、动作和对白 |
| `DOSSIER_EPISTOLARY` | 档案或书信体 | 使用档案、报告、书信或日记推进叙事 |

工作流会把输出风格作为硬约束传递给头脑风暴、方向评估、候选稿、终稿和质量审核。终稿只有在综合分与 `genreComplianceScore` 均达到质量阈值时才会进入 `COMPLETED`。

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

## Agent 平台内核

项目包含三个相互独立但可以组合使用的核心组件：

- Agent Runtime：执行“模型决策、能力调用、观察结果、继续或结束”的多轮循环。
- Capability Registry：保存 Agent 可使用的能力、模型工具名、输入 Schema 和提供方信息。
- MCP Invocation Service：管理 MCP 长连接、发现 Tools、校验参数并执行调用。

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

当前嵌入模型只支持 Ollama，通过 `OLLAMA_EMBEDDING_MODEL` 指定模型名称。

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
$env:GITHUB_ALLOWED_REPOSITORIES = "langchain4j/langchain4j,melon1010/LangChain4j-Springboot"
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

## 构建可执行包

```powershell
mvn clean package
java -jar target/openmanus-springboot-0.0.1-SNAPSHOT.jar
```

## 常见问题

- 页面能打开但模型无回复：确认所选模型服务已启动、API Key 有效，且 `base-url` 使用 OpenAI 兼容地址而不是 Anthropic 专用地址。
- 文档上传失败：确认 Redis 已启动；超过 Spring 上传限制的文件会被拒绝。
- 文档无法回答：确认 Ollama 已启动并已下载嵌入模型；复杂文档建议安装 Docling，否则只能回退解析 PDF 和 TXT。
- GitHub 查询失败：确认 Node.js、`npx` 和 `GITHUB_TOKEN` 可用。
- 网页抓取失败：确认已安装 Python 环境和 `uvx`，并且目标网站可访问。
- 端口冲突：通过 `SERVER_PORT` 覆盖 Spring Boot 默认端口，例如 `$env:SERVER_PORT = "8081"`。

主要配置位于 `src/main/resources/application.yml`。API Key 和访问令牌均通过环境变量注入，请勿将真实凭据提交到仓库。
