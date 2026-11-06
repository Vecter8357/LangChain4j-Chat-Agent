# Knowledge 第一版

管理入口为 `/knowledge`；普通对话页 `/chat` 左侧可选择多个知识库。

## 生命周期和存储

| 内容 | 存储 | 生命周期 |
| --- | --- | --- |
| 对话临时附件 | MinIO + Redis 元数据 + Qdrant | 当前回答结束自动清理，仅走 `/api/ai/upload` |
| 知识库文档 | MinIO + MySQL 元数据、版本及任务 + Qdrant | 永久保留，只有显式删除文档或知识库才清理 |
| 文档分块和全文索引 | MySQL `la4j_kb_chunk` | 随索引版本保留，文档删除后清理正文 |

MySQL 默认使用 `knowledge.datasource=szc_frjzk` 指定的物理数据源，不跟随业务 `@DS` 路由。启动时只执行 `db/knowledge-v1.sql` 中的幂等建表语句，表名前缀为 `la4j_kb_`。可用 `KNOWLEDGE_DATASOURCE` 更换目标库，`KNOWLEDGE_INITIALIZE_SCHEMA=false` 关闭自动建表。现有其他业务表不参与本功能。

五张表分别保存知识库、文档、文档原文版本、索引版本、分块。文档与版本记录保留删除审计信息；原文对象、向量及分块正文在删除任务完成后清理。

## 异步索引与版本

上传接口返回 HTTP 202。后台扫描 MySQL 队列，使用有界线程池处理任务；文档状态包含 `UPLOADING → QUEUED → PROCESSING → READY/FAILED`。默认两个索引线程，任务使用 owner + 60 秒租约，每 10 秒续租。进程中断后过期任务可被重新领取，多次中断后进入失败状态。索引失败可在页面点击“重建”。

每次上传新内容增加文档版本；每次重建生成独立索引 ID 和独立分块 ID，记录切片算法、长度、重叠量、Embedding 模型名、配置指纹、维度和 Qdrant Collection。切换 `active_index_id` 与索引 READY 状态在同一个 MySQL 事务中完成，新索引失败不影响旧生效索引。

知识库事务采用 READ_COMMITTED，并通过显式行锁保护版本切换；InnoDB 全文索引分块写入遇到瞬时死锁或锁等待冲突时，会在整批事务回滚后最多尝试三次。

Embedding 版本指纹由 provider、服务地址、模型名和 `KNOWLEDGE_EMBEDDING_REVISION` 计算，不包含密钥。该修订号是显式模型版本，不是自动下载的权重摘要。**同名模型标签（例如 latest）对应的权重发生变化时，应修改修订号并重建文档索引。** 更换维度时，还需要新建匹配维度的 Collection。旧 Embedding 版本会被排除出当前向量召回，仍可参与全文检索，响应中会给出重建提示。

默认使用硅基流动 `BAAI/bge-m3`，实测输出 1024 维，接口为 `https://api.siliconflow.cn/v1/embeddings`。默认 Collection 为 `la4j_agent_sf_bge_m3`，与旧 Qwen Collection 分开；已有文档需要重新建立索引。当前显式修订号为 `siliconflow-bge-m3-v1`，提供方版本变化时应更新修订号。模型配置与指纹已支持提供方切换，临时附件检测到旧配置也会重新向量化。

无需安装或启动本地 Embedding 模型，只需设置密钥并让启动进程继承环境变量：

```powershell
$env:GJLD_EMBEDDING = "你的硅基流动API Key"
```

## 检索与 Rerank

1. MySQL 先计算指定知识库、可选文件范围内的当前生效索引。
2. Qdrant 按 `scope=knowledge`、`kb_id`、`index_id`、可选 `kb_document_id` 进行向量过滤召回。
3. MySQL 使用 ngram FULLTEXT 执行中文全文召回（需要 MySQL 内置 ngram parser）。
4. 两路各取候选，使用 RRF（k=60）融合，按分块 ID 去重。
5. 对融合候选执行 Rerank；返回前再次检查索引可见性，排除已删除或已切换版本。

默认重排器为硅基流动 `BAAI/bge-reranker-v2-m3`，使用与 Embedding 相同的 `GJLD_EMBEDDING` 密钥。Reranker 只返回相关性分数，不能用作 Embedding 模型。以下为默认配置对应的可选覆盖项：

```powershell
$env:KNOWLEDGE_RERANK_PROVIDER = "remote"
$env:KNOWLEDGE_RERANK_URL = "https://api.siliconflow.cn/v1/rerank"
$env:KNOWLEDGE_RERANK_MODEL = "BAAI/bge-reranker-v2-m3"
# 如需独立重排密钥，可设置 KNOWLEDGE_RERANK_API_KEY；默认读取 GJLD_EMBEDDING。
```

请求发送 `model/query/documents/top_n`，返回 `results[{index,relevance_score}]`。索引与分数会被校验。远程故障时显式回退本地重排，响应中的 `reranker` 和 `warnings` 会反映实际行为；向量服务故障时也会明确告知仅使用全文召回。

检索响应包含分块正文、文档名、知识库 ID、文件 ID、文档版本、索引版本、分块版本、Embedding 版本、分块序号及来源链接。当前来源精度为分块，不伪造 PDF 页码。全文 `lexicalScore` 为全文候选排名的倒数，向量分数和 RRF 分数独立展示。

## 删除一致性

删除先在 MySQL 标记 `DELETING`，检索立即隐藏文档；后台取得同一文档租约后删除所有历史索引向量及原文版本，再清理分块并提交 `DELETED`。清理失败保持 `DELETE_FAILED`，每 30 秒自动重试，最多五次，之后可手动重试。重复删除幂等。

上传在对象写入期间同样持有文档租约，避免删除任务先完成、在途上传后写回。对象请求设有超时。删除发生于索引期间时，不允许新索引提交生效。跨 MySQL、Qdrant、MinIO 使用可恢复任务实现最终一致性，而非跨系统 ACID 事务。

## API 和 Agent

- `GET/POST /api/knowledge/bases`：列表、新建。
- `DELETE /api/knowledge/bases/{baseId}`：异步删除知识库。
- `GET/POST /api/knowledge/bases/{baseId}/documents`：文档列表、上传。
- `POST .../documents/{documentId}/versions`：上传新文档版本。
- `GET .../documents/{documentId}/versions`：原文和索引版本。
- `POST .../documents/{documentId}/rebuild`：重新索引或失败重试。
- `GET .../documents/{documentId}/chunks?indexId=...`：预览分块（最多500块）。
- `GET .../documents/{documentId}/content?version=...`：下载指定原文版本。
- `DELETE .../documents/{documentId}`：异步删除及失败重试。
- `POST /api/knowledge/search`：`question/knowledgeBaseIds/fileIds/limit`，结构化混合检索结果。
- `POST /api/knowledge/chat`：`input/knowledgeBaseIds/fileIds`，其中 fileIds 指本轮临时附件；永久知识库来源由服务端附在答案后。

已注册 `knowledge.search` Capability。Agent 或 DAG 的 AGENT 节点选择该能力时，必须在结构化输入中提供 `knowledgeBaseIds`；如需进一步限制文件，传入 `knowledgeFileIds`。Runtime 会阻止模型扩大这些范围。当前是单用户平台的请求范围隔离，不包含多用户权限系统。

## 验证

```powershell
mvn "-Dknowledge.integration=true" "-Dmysql.integration=true" "-Dqdrant.integration=true" test
```

Knowledge 联调连接真实 MySQL、MinIO、Qdrant，但使用可控测试 Embedding，覆盖版本切换、库/文件隔离、模型失败后保留旧索引、租约恢复、过期 owner 拒绝提交、删除失败重试、上传/删除竞争及外部对象清理。测试创建的数据会清理。该测试不等同于真实模型的语义质量评测。远程 Rerank 适配器有本地 HTTP 合约测试，不默认连接收费接口。
