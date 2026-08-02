# Ragent API 文档

## 0. 总览

| 项 | 值 |
|----|----|
| Base URL | `http://<host>:9090/api/ragent` |
| 端口 | `9090`(context-path `/api/ragent`) |
| 前端 | `5173`(开发) / `5177`(Nginx 生产) |

**鉴权规则**:Sa-Token 全局拦截 `/**`,仅放行 `/auth/**`、`/files/**`、`/error`。除登录接口外,请求头需携带登录返回的 Token:

```
Authorization: <token>
```

用户管理接口(`/users*`)额外要求 `admin` 角色。

**统一响应体**:

```json
{
  "code": "0",
  "message": "Success",
  "data": { }
}
```

| code | 含义 |
|------|------|
| `0` | 成功 |
| `B000001` | 客户端错误(参数/业务校验失败) |
| `B000002` | 服务端错误 |
| 其他 | 具体业务错误码 |

---

## 1. 认证 AuthController

| Method | Path | 说明 | 鉴权 |
|--------|------|------|:--:|
| POST | `/auth/login` | 用户登录,返回 token | 免 |
| POST | `/auth/logout` | 用户登出,清除会话 | 免 |

**登录请求** `LoginRequest`:

```json
{ "username": "admin", "password": "admin" }
```

**登录响应** `LoginVO`:

```json
{ "userId": "...", "role": "admin", "token": "...", "avatar": "..." }
```

---

## 2. 用户中心 UserController

| Method | Path | 说明 | 鉴权 |
|--------|------|------|:--:|
| GET | `/user/me` | 获取当前登录用户信息 | 登录 |
| GET | `/users` | 分页查询用户列表 | admin |
| POST | `/users` | 创建用户 | admin |
| PUT | `/users/{id}` | 更新用户 | admin |
| DELETE | `/users/{id}` | 删除用户 | admin |
| PUT | `/user/password` | 修改当前用户密码 | 登录 |
| PUT | `/user/profile` | 当前用户修改资料(用户名/头像) | 登录 |
| POST | `/user/avatar/upload` | 上传头像(multipart `file`) | 登录 |

**头像上传限制**:jpg/jpeg/png/gif/webp,≤ 5MB,以文件头魔数校验真实类型。返回图片 URL:

```json
{ "code": "0", "data": "/api/ragent/files/avatars/{uuid}.{ext}" }
```

**资料更新** `ProfileUpdateRequest`:`username`(可选,默认 admin 禁止修改)、`avatar`(可选,空串表示清除头像)。

---

## 3. 知识库 / 文档 / Chunk

### 3.1 知识库 KnowledgeBaseController

| Method | Path | 说明 |
|--------|------|------|
| POST | `/knowledge-base` | 创建知识库 |
| PUT | `/knowledge-base/{kbId}` | 重命名知识库 |
| DELETE | `/knowledge-base/{kbId}` | 删除知识库 |
| GET | `/knowledge-base/{kbId}` | 知识库详情 |
| GET | `/knowledge-base` | 分页查询知识库 |
| GET | `/knowledge-base/chunk-strategies` | 支持的分块策略列表 |

### 3.2 文档 KnowledgeDocumentController

| Method | Path | 说明 |
|--------|------|------|
| POST | `/knowledge-base/{kbId}/docs/upload` | 上传文档(multipart) |
| POST | `/knowledge-base/docs/{docId}/chunk` | 分块并写入向量库 |
| DELETE | `/knowledge-base/docs/{docId}` | 删除文档(可选删除向量) |
| GET | `/knowledge-base/docs/{docId}` | 文档详情 |
| PUT | `/knowledge-base/docs/{docId}` | 更新文档信息 |
| GET | `/knowledge-base/{kbId}/docs` | 分页查询文档列表 |
| GET | `/knowledge-base/docs/search` | 全局文档检索建议 |
| PATCH | `/knowledge-base/docs/{docId}/enable` | 启用/禁用文档 |
| GET | `/knowledge-base/docs/{docId}/chunk-logs` | 文档分块日志 |

### 3.3 Chunk KnowledgeChunkController

| Method | Path | 说明 |
|--------|------|------|
| GET | `/knowledge-base/docs/{docId}/chunks` | 分页查询 Chunk |
| POST | `/knowledge-base/docs/{docId}/chunks` | 新增 Chunk |
| PUT | `/knowledge-base/docs/{docId}/chunks/{chunkId}` | 更新 Chunk |
| DELETE | `/knowledge-base/docs/{docId}/chunks/{chunkId}` | 删除 Chunk |
| PATCH | `/knowledge-base/docs/{docId}/chunks/{chunkId}/enable` | 启用/禁用 Chunk |
| PATCH | `/knowledge-base/docs/{docId}/chunks/batch-enable` | 批量启用/禁用 Chunk |

---

## 4. 入库流水线

| Method | Path | 说明 |
|--------|------|------|
| POST | `/ingestion/pipelines` | 创建流水线 |
| PUT | `/ingestion/pipelines/{id}` | 更新流水线 |
| GET | `/ingestion/pipelines/{id}` | 流水线详情 |
| GET | `/ingestion/pipelines` | 分页查询流水线 |
| DELETE | `/ingestion/pipelines/{id}` | 删除流水线 |
| POST | `/ingestion/tasks` | 创建并执行采集任务 |
| POST | `/ingestion/tasks/upload` | 上传文件触发采集任务 |
| GET | `/ingestion/tasks/{id}` | 任务详情 |
| GET | `/ingestion/tasks/{id}/nodes` | 任务节点运行记录 |
| GET | `/ingestion/tasks` | 分页查询任务 |

---

## 5. RAG 对话(SSE 核心)

### 5.1 发起流式对话

```
GET /rag/v3/chat?question=...&conversationId=...&deepThinking=true
Accept: text/event-stream
```

| 参数 | 必填 | 说明 |
|------|:--:|------|
| `question` | 是 | 用户问题 |
| `conversationId` | 否 | 会话 ID,缺省创建新会话 |
| `deepThinking` | 否 | 深度思考模式 |

**SSE 事件协议**:

| 事件 | 载荷 | 说明 |
|------|------|------|
| `meta` | `MetaPayload`(conversationId, taskId) | 会话与任务元信息 |
| `references` | JSON 引用数组 | 检索引用(TEXT/IMAGE/HYPERGRAPH) |
| `message` | `MessageDelta`(type, content) | 增量消息 |
| `finish` | `CompletionPayload`(messageId, title) | 模型回复完成 |
| `reject` | `MessageDelta` | 请求被拒绝(限流/排队) |
| `done` | `[DONE]` | 完成终止 |

**引用对象** `Reference`:

```json
{
  "type": "TEXT | IMAGE | HYPERGRAPH",
  "label": "来源标题",
  "url": "/files/drawings/xxx.jpg",
  "detail": "描述",
  "snippet": "片段",
  "extra": {}
}
```

### 5.2 停止任务

| Method | Path | 说明 |
|--------|------|------|
| POST | `/rag/v3/stop` | 停止指定 `taskId` 的任务 |

---

## 6. 会话 / 反馈 / 意图 / 追踪 / 设置

| Method | Path | 说明 |
|--------|------|------|
| GET | `/conversations` | 当前用户会话列表 |
| PUT | `/conversations/{conversationId}` | 重命名会话 |
| DELETE | `/conversations/{conversationId}` | 删除会话 |
| GET | `/conversations/{conversationId}/messages` | 会话消息列表 |
| POST | `/conversations/messages/{messageId}/feedback` | 点赞/踩反馈(异步 MQ) |
| GET | `/intent-tree/trees` | 完整意图节点树 |
| POST | `/intent-tree` | 创建意图节点 |
| PUT | `/intent-tree/{id}` | 更新意图节点 |
| DELETE | `/intent-tree/{id}` | 删除意图节点 |
| POST | `/intent-tree/batch/enable` | 批量启用节点 |
| POST | `/intent-tree/batch/disable` | 批量停用节点 |
| POST | `/intent-tree/batch/delete` | 批量删除节点 |
| GET | `/mappings` | 分页查询关键词映射规则 |
| GET/POST/PUT/DELETE | `/mappings(/{id})` | 映射规则 CRUD |
| GET | `/rag/traces/runs` | 分页查询链路运行记录 |
| GET | `/rag/traces/runs/{traceId}` | 链路详情(含节点) |
| GET | `/rag/traces/runs/{traceId}/nodes` | 链路节点列表 |
| GET | `/rag/sample-questions` | 随机示例问题(欢迎页) |
| GET | `/sample-questions` | 分页查询示例问题 |
| GET/POST/PUT/DELETE | `/sample-questions(/{id})` | 示例问题 CRUD |
| GET | `/rag/settings` | 系统 RAG/AI 模型配置 |
| GET | `/admin/dashboard/overview` | 看板总览统计 |
| GET | `/admin/dashboard/performance` | 看板性能指标 |
| GET | `/admin/dashboard/trends` | 看板趋势图数据 |

---

## 7. MCP Server(独立模块)

| Method | Path | 说明 |
|--------|------|------|
| POST | `/mcp` | MCP Streamable HTTP JSON-RPC 端点(无鉴权) |

支持方法:`initialize`、`tools/list`、`tools/call`。

内置工具:

| 工具 | 说明 |
|------|------|
| `weather_query` | 查询城市天气 |
| `ticket_query` | 查询技术支持工单数据 |
| `sales_query` | 查询软件销售数据 |

---

## 8. LLM 路由配置

| 能力 | 默认模型 | 候选 |
|------|----------|------|
| Chat | `qwen3-max` | `qwen-plus`(百炼) / `qwen3-max`(百炼,思考) / `glm-4.7`(SiliconFlow,思考) / `qwen3-local`(Ollama) |
| Embedding | `qwen-emb-8b` | `Qwen/Qwen3-Embedding-8B`(SiliconFlow, 4096 维) / `qwen3-embedding:8b-fp16`(Ollama) |
| Rerank | `qwen3-rerank` | `qwen3-rerank`(百炼) / `rerank-noop`(兜底) |

故障切换:`ai.selection.failure-threshold=2`,`open-duration-ms=30000`。

---

## 9. Milvus Schema

| 集合 | 用途 |
|------|------|
| `rag_default_store`(默认)/ 各知识库独立集合 | 文本向量 |
| `industrial_images` | 图像语义(图纸描述) |

统一 4 列 Schema:`id`(VarChar 主键)、`content`(VarChar 65535)、`metadata`(JSON)、`embedding`(FloatVector 4096)。索引 HNSW,metric COSINE。向量库类型通过 `rag.vector.type` 切换 milvus / pg。
