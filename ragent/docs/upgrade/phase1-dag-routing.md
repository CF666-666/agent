# Phase 1-A：ETL 显式边与条件路由

**状态：已实现，已完成真实 PostgreSQL 与 HTTP 接口联调**
**日期：2026-08-03**

## 本次目标

将原本由 `NodeConfig.nextNodeId` 表达的单链执行，升级为可持久化的有向无环图（DAG）路由；保留旧配置的运行兼容性。本次不包含重试、超时、断点续跑、幂等控制或分支并行。

## 设计决策

- 新增 `t_ingestion_pipeline_edge`，一条边一行，不将边列表塞入 JSON。这样可以按流水线/来源节点索引，并为前端画布和后续执行审计保留稳定的边 ID。
- `NodeConfig.condition` 仍表示“当前节点是否执行”；`NodeEdge.condition` 专用于“当前节点执行后选择哪一跳”。二者不混用。
- 条件边按 `priority` 从高到低依次判断；第一个命中的条件边生效；无命中时才走唯一的 `defaultEdge`。同一来源节点的条件边优先级不得重复，避免由数据顺序决定路由。
- 只要某节点存在显式出边，运行时便忽略该节点的旧 `nextNodeId`；没有显式出边的旧节点会在内存中折算为默认边。因此存量单链流水线无需迁移即可继续运行。
- 图定义在保存和执行前均经 `PipelineGraph` 校验：节点/边引用、单起点、可达性、至少一个终点、DFS 环检测、默认边唯一性和条件边优先级唯一性。
- JSON `regex` 条件会在保存时预编译校验；运行时若条件求值仍抛出异常，执行引擎会将任务标记为 `FAILED` 并追加路由失败日志，保证任务收尾逻辑继续持久化错误状态。
- 节点与边均采用“整图替换”保存，替换时在同一事务内物理删除旧配置再写入新配置；避免节点逻辑删除字段参与唯一键时，第二次编辑与历史行冲突。任务及任务节点日志仍保留原有持久化记录。
- `t_ingestion_task_node.node_order` 改为按实际节点日志的产生顺序写入，能够正确反映未来分支路径，而非依据单链静态推导。

## API 契约

`POST /ingestion/pipelines` 和 `PUT /ingestion/pipelines/{id}` 均支持：

```json
{
  "nodes": [
    {"nodeId": "fetch", "nodeType": "fetcher"},
    {"nodeId": "pdf-parser", "nodeType": "parser"},
    {"nodeId": "ocr-parser", "nodeType": "parser"}
  ],
  "edges": [
    {
      "fromNodeId": "fetch",
      "toNodeId": "pdf-parser",
      "priority": 100,
      "condition": {"field": "mimeType", "operator": "eq", "value": "application/pdf"}
    },
    {
      "fromNodeId": "fetch",
      "toNodeId": "ocr-parser",
      "defaultEdge": true
    }
  ]
}
```

读取响应中的边使用 `edgeId`，前端更新时应原样带回。更新接口中：

- `edges: null` 或省略：保留已有显式边；
- `edges: []`：删除已有显式边，相关节点会恢复使用其旧 `nextNodeId`（如果有）；
- `nodes` 同样仅在显式传入时整体替换。

## 数据库发布

- 新环境：`resources/database/schema_pg.sql` 已包含新表。
- 已有 PostgreSQL：部署前执行 [20260803_add_ingestion_pipeline_edge.sql](../../resources/database/migrations/20260803_add_ingestion_pipeline_edge.sql)。项目当前没有 Flyway/Liquibase 自动迁移，因此该 SQL 必须由部署流程执行一次。

## 验证记录

已执行：

```text
mvn -pl bootstrap -am test -Dtest=PipelineGraphTest -Dsurefire.failIfNoSpecifiedTests=false
```

结果：7/7 通过，覆盖条件优先级、默认边回退、`nextNodeId` 兼容、空图、DFS 环检测、同优先级冲突、非法正则，以及路由求值异常时的失败状态落盘前置行为。

## 简历边界

现在可以真实描述为：

> 独立实现可配置文档入库 DAG 编排内核，基于显式边表支持 SpEL/JSON 条件分支与默认路由，并通过图校验、节点级执行日志和任务节点持久化保障前端编排与故障定位。

尚不能写入简历：全节点重试、超时控制、断点续跑、幂等入库、并行分支调度；这些仍需后续闭环。

## Phase 1-B：安全节点重试与尝试日志

已新增节点级 `executionPolicy`：

```json
{"maxAttempts": 3, "retryBackoffMs": 500}
```

- 未配置策略时保持原行为：仅执行一次。
- `maxAttempts` 范围为 1~5，退避范围为 0~60000ms。
- 仅 `fetcher`、`parser`、`multimodal_parse` 可以配置多次尝试；`indexer`、分块和 LLM 增强节点在具备幂等保障前拒绝重试，避免重复向量写入或部分上下文变更。
- 每次尝试都记录在 `t_ingestion_task_node.attempt`，并按真实执行先后排序；最终任务状态仍由最终尝试决定。
- 新环境 schema 已含字段；已有库还需执行 [20260803_add_ingestion_node_execution_policy.sql](../../resources/database/migrations/20260803_add_ingestion_node_execution_policy.sql)。

本闭环刻意不实现硬超时：节点共享可变 `IngestionContext`，在线程中断后仍可能继续写入上下文。需要先引入协作式取消或隔离的执行上下文，才能安全支持超时和断点续跑。

验证：`PipelineGraphTest`、`IngestionEngineRouteFailureTest`、`NodeExecutionRunnerTest` 共 8/8 通过。

简历更新边界：可写“安全节点级重试、尝试日志和文档版本幂等”；不可写“全节点重试、超时或断点续跑”。

## Phase 1-C：任务幂等与确定性向量写入

- 任务键由“上传文件内容哈希（或外部来源标识）+ 流水线定义指纹 + 向量空间”生成。
- 相同键处于 `running` 或 `completed` 时复用既有任务；失败任务不占用唯一键，可重新提交。
- 索引节点基于任务键和 chunk 序号生成 20 位确定性 chunk ID，向量存储的 upsert 会覆盖同一文档版本，避免失败后重试产生重复向量。
- 已有库执行 [20260803_add_ingestion_task_idempotency.sql](../../resources/database/migrations/20260803_add_ingestion_task_idempotency.sql)。

边界：上传场景按实际字节去重；未抓取内容的 URL/S3 等来源目前按来源标识去重，后续可在 Fetcher 完成后升级为内容哈希确认。

## Phase 1-D：上传任务检查点与失败续跑

上传任务创建后会立即独立提交任务记录、原始字节和不可变的 `PipelineDefinition` 快照。每个节点成功且下一跳路由已确定后，执行引擎通过 `IngestionExecutionListener` 调用 `TaskCheckpointStore`，持久化：

- `last_success_node_id` 和 `next_node_id`：恢复时从下一跳开始，不重复已成功节点；
- `checkpoint_json`：文本、结构化文档、切块、增强结果、关键词和元数据；
- 精简后的节点日志：不把大输出重复写入检查点；
- `resume_count`：记录人工触发恢复的次数。

`POST /ingestion/tasks/{id}/resume` 仅接受 `failed` 的上传任务，并通过 `status = failed` 的条件更新原子认领任务，避免两个恢复请求同时执行同一个检查点；恢复时读取保存的原始字节与流程快照，而不是当前在线流程配置。流程快照会递归脱敏 `token`、`secret`、`password`、`apiKey`、`credential`、`authorization` 等字段。`VectorChunk.embedding` 不进入 JSON 检查点，恢复后会对保存的切块重新嵌入，以保持索引节点输入完整。失败前已经成功写入的索引节点仍通过 Phase 1-C 的确定性文档 ID 做定向覆盖。

已有 PostgreSQL 需要执行 [20260804_add_ingestion_task_checkpoint.sql](../../resources/database/migrations/20260804_add_ingestion_task_checkpoint.sql)。本阶段不保存 URL/S3/飞书凭据；异常退出后仍处于 `running` 的孤儿任务由后续的 Phase 1-E 租约认领机制处理。

## Phase 1-E：孤儿运行任务租约认领

任务初始化和每次成功检查点都会持有、续期一个 30 分钟执行租约（`execution_lease_token`、`lease_expires_at`）。检查点与收尾更新都必须携带同一个 token；如果旧执行器已失去租约，写入会失败，不会覆盖新执行器的进度。

恢复接口原子认领两类任务：`failed`，或租约已过期的 `running` 上传任务。仍在有效租约内的 `running` 任务会被拒绝，避免人工重复点击触发并发执行。历史 `running` 任务在部署 [20260804_add_ingestion_task_lease.sql](../../resources/database/migrations/20260804_add_ingestion_task_lease.sql) 后被标记为立即过期，可通过现有恢复接口认领。

真实 PostgreSQL 验收（本地 `ragent-postgres`）：先运行 `TaskCheckpointStorePostgresIntegrationTest`，确认旧库缺少 `idempotency_key`、检查点与载荷表时测试失败；按顺序执行五份 Phase 1 迁移后复跑通过。该测试覆盖失败任务首个认领、有效租约拒绝再次认领、过期 `running` 任务换发新租约后被接管。测试结束会物理清理自身创建的任务与载荷。

验证：`IngestionEngineRouteFailureTest` 新增断言，确认每个成功节点在路由已解析后都会触发一次检查点回调；`TaskCheckpointStoreTest` 覆盖敏感字段递归脱敏；与幂等、路由、重试测试合计 11 项通过。

### Phase 1-E.1：PostgreSQL JSONB 落库与 HTTP 恢复验收

真实 HTTP 验收发现并修复了一个 PostgreSQL 方言问题：MyBatis-Plus 的 `LambdaUpdateWrapper.set(...)` 在此更新路径中没有应用实体字段声明的 `JsonbTypeHandler`，导致 JSON 字符串被按 `varchar` 绑定到 `jsonb` 列。节点已执行成功后，`TaskCheckpointStore.complete(...)` 会因此失败，表现为恢复接口返回系统错误且任务不能收敛。

`TaskCheckpointStore` 现对 `checkpoint_json`、`logs_json` 和 `metadata_json` 使用 `CAST({0} AS jsonb)` 写入；任务初始插入仍沿用实体的 `JsonbTypeHandler`。本地临时实例完成了认证后的真实接口回放：构造一个带原始载荷、状态为 `failed` 的单 `fetcher` 上传任务，调用 `POST /ingestion/tasks/{id}/resume` 后返回 `completed`，并确认 `resume_count=1`、`last_success_node_id=fetch`、`lease_expires_at IS NULL`。专用验证任务、载荷和节点日志均已物理清理。

新增 `TaskCheckpointStorePostgresIntegrationTest#shouldPersistCheckpointAndCompletionJsonToPostgresJsonbColumns`，覆盖检查点与收敛阶段的 JSONB 写入。该用例已通过真实 PostgreSQL 验证：

```text
mvn -pl bootstrap -am '-Dtest=TaskCheckpointStorePostgresIntegrationTest#shouldPersistCheckpointAndCompletionJsonToPostgresJsonbColumns' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

此前仅指定 `-pl bootstrap` 时，Maven 会复用本地仓库中旧版 `framework` JAR，进而误报 `RetrievedChunk.metadata` 缺失；该现象不是当前源码或测试不兼容。依赖本仓库模块的定向测试必须带 `-am`，以使 Reactor 使用同一工作区中的 `framework` 源码。
