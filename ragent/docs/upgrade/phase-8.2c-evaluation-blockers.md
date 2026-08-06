# Phase 8.2-C 评测阻塞与修复记录

> 状态：已收口。本文只记录已经由本地运行时或原始报告确认的事实；未完成修复和未重跑的结果不得用于简历、README 或对外指标。

## 当前边界

- 已完成：阶段 2B 的受控 Demo 超边导入。以 `phase5.import-demo-hyperedges=true` 启动时，`data/hypergraph/hyperedges.jsonl` 的 603 条超边已写入 PostgreSQL，并由运行时加载器载入内存图。
- 已完成：请求级检索开关、分层数据集、分批报告、case 级防重和运行时元数据能力。
- 已完成：阶段 8.2-C 的 A（文本）、B（图像）、C（超图）、D（全链路）四组隔离环境重跑与正式结论。

## 已确认问题

### P0（已修复）：图像样本暴露的检索前意图分类长尾

**症状**

- 在启动参数 `phase5.import-demo-hyperedges=true` 且 `rag.rate-limit.global.enabled=false` 的本地服务上，B 图像组首个 5 条批次（请求超时 12 秒）出现 3 条未收到 `references` 事件。
- 对应原始诊断报告：`scripts/eval/report/industrial_eval_v2_B_isolated_00.json`。该文件不是正式基线，不得纳入统计结论。

**根因与修复**

- 同一固定样本关闭图像和超图通道后仍超时，排除了图像集合/Milvus 作为首要阻塞点。
- 服务端日志显示百炼同步意图分类请求因 `Arrearage` 失败后进入模型路由降级，意图分类约 50 秒后才结束；后续全局向量检索约 0.5 秒完成。
- `IntentResolver` 现使用 `rag.intent-resolve.timeout-millis`（默认 2000ms）约束每个子问题分类；超时后取消任务并返回空意图，让全局向量检索继续执行。
- 回归测试覆盖阻塞分类器的降级行为；固定图像样本 HTTP 重跑在 12 秒内收到 10 条 references，期望图像通道和来源 ID 均命中。

**已排除因素**

- 不由全局聊天队列排队引起：该次运行已关闭 `rag.rate-limit.global.enabled`。
- 不由空超图引起：服务启动日志确认导入并加载 603 条超边；图像组同时关闭了超图通道。
- 不应再由外部 Rerank 余额不足直接中断：`RerankPostProcessor` 已在 Rerank 运行时异常时降级返回原候选 Top-K（提交 `5e958bb`）。

**仍待验证项**

- 图像组 25 条完整重跑的延迟分布与通道命中率；单样本已验证不代表全量指标。

**下一闭环**

1. 重跑图像组 25 条，确认无同类检索前阻塞并得到正式延迟分布。
2. 重跑超图、文本和全链路组，生成统一运行时元数据的正式报告。
3. 若全量图像组仍有长尾，再按图像检索、后处理和 SSE 投影分段定位。

**验收条件**

- 同一固定样本可稳定复现修复前问题或明确的超时边界。
- 修复后该样本连续运行可在约定超时内收到 `references`；日志中无未处理异常。
- 完成图像组 25 条重跑，记录 Hit@K、MRR、期望通道命中率和延迟分位数；指标按原始报告计算，不人工编造。

### P0：评测容易被全局聊天队列污染

**症状**

- `application.yaml` 将 `rag.rate-limit.global.max-concurrent` 配置为 `1`。
- 评测脚本在收到 `references` 后主动断开 SSE；服务端请求尚未彻底释放队列许可时，后续样本会排队并在客户端读取超时前无法开始，从而被误记为“未检索到”。

**已完成的缓解**

- 对隔离评测实例使用 `rag.rate-limit.global.enabled=false`，测量检索通道而非排队吞吐。
- 评测报告新增 `runtime` 元数据（运行时标签和请求超时），合并器拒绝混合不同运行时的报告（提交 `00946f2`）。

**后续约束**

- 正式 A/B/C/D 报告必须统一 `runtime.label=seeded-hyperedges-rate-limit-disabled`，并在报告中保留 `request_timeout_seconds`。
- 若要评估生产排队能力，应另建限流压测，不得和检索质量基线混合。

### P1：历史诊断报告不可作为正式基线

**原因**

- 部分报告生成于空超图持久层、Rerank 外部错误或聊天队列开启的运行时。
- 部分报告生成早于 `case_id` 与 `runtime` 元数据，无法满足当前合并校验要求。

**处理原则**

- 保留原始文件用于排障；不提交为正式评测结果，不更新简历指标。
- 图像长尾修复后，从隔离服务完整重跑 A/B/C/D，并通过 `merge_eval_reports.py` 生成唯一的正式合并报告。

## 已修复但仍需端到端复验的问题

### Rerank 外部服务余额不足导致检索结果整体丢失

- 运行时证据：文本向量检索已返回 30 个 Chunk，但百炼 Rerank 返回 `HTTP 400 / Arrearage`，异常使 SSE references 未发送。
- 修复：`RerankPostProcessor` 在 `RuntimeException` 时记录降级并返回预排序候选的 Top-K；成功路径不变。
- 验证：`RerankPostProcessorTest` 覆盖成功与降级路径；相关定向测试 9 项通过，Maven `BUILD SUCCESS`。
- 待验：图像长尾修复后，以 HTTP 评测证明外部 Rerank 失败时仍可稳定收到 references。

## 执行顺序

1. 图像长尾最小重现与分段计时（P0）。
2. 图像检索/投影边界修复及回归测试（P0）。
3. 隔离运行时重跑 B 与 C，确认图像和超图通道独立可用。
4. 重跑 A 与 D，合并四组 schema v2 报告。
5. 更新 `docs/evaluation-report.md`、路线图和简历数字；仅使用重跑后的真实数据。

## 2026-08-06：图像通道长尾的二次定位与修复

### 已确认根因

- `image_debug_fixed_probe.json` 证明意图分类超时降级后，固定图像样本可以在 12 秒内返回 10 条 `references`；这只排除了检索前的分类长尾。
- 后续 B 组分批运行仍有图像样本在客户端 12 秒内未收到 `references`。服务端日志显示文本向量通道约 2.5 秒完成，而 `ImageSearchChannel` 单次执行约 11.2 秒；SSE 引用投影发生在图像通道结束之后，因此客户端已先超时。

### 修复

- `SearchChannel` 新增默认的通道执行预算扩展点；默认不设时限，避免改变既有文本和超图通道的语义。
- `ImageSearchChannel` 读取 `rag.search.channels.image-semantic.timeout-millis`，默认 3000ms。
- `MultiChannelRetrievalEngine` 对设置预算的通道使用 `completeOnTimeout` 返回带 `timedOut=true` 的空结果；已完成的文本/超图结果继续进入后处理和 SSE 引用投影。
- 单元回归 `MultiChannelRetrievalEngineTest` 用 50ms 慢图像通道和即时文本通道复现原问题：修复前约 1034ms 失败，修复后约 76ms 返回文本证据。

### 仍待完成的验证

- 重启隔离评测实例，重跑 B 组 25 条，确认慢图像通道不再导致 `references` 缺失，并记录 `timedOut` 降级比例。
- 图像通道超时后的结果质量不能计入图像 Recall@K；该指标须在图像后端长尾收敛后另行优化和重跑。

## 2026-08-06：分类线程池饱和绕过超时预算

### 根因与修复

- B 组首批第 5 条仍超时时，日志显示请求进入聊天服务后约 11 秒才到达 `IntentResolver` 的 2000ms 超时日志；图像检索和向量检索本身均在约 1 秒内完成。
- 原因是分类线程池使用 `SynchronousQueue + CallerRunsPolicy`。池饱和时，`CompletableFuture.supplyAsync` 在 `chat_entry_executor` 同步执行外部分类调用，因而无法进入 `task.get(timeout)` 的预算保护。
- 分类池现改用 `AbortPolicy`；`IntentResolver` 捕获 `RejectedExecutionException` 后立即返回空意图，使请求安全降级到全局向量检索。

### 验证

- `IntentResolverTest` 新增“分类执行器拒绝提交”回归用例；连同图像通道预算、Rerank 降级、请求开关和超图路径的 12 项定向测试均通过。
- 重启干净的隔离实例后，B 组 offset 0--4 在 12 秒预算内全部收到 10 条 `references`，`no_retrieval=0`；报告为 `scripts/eval/report/industrial_eval_v2_B_intent-budget_00.json`。该报告仅为分批验证，尚未并入正式全量结果。

## 2026-08-06：检索专用评测模式隔离

### 根因

- 评测脚本此前在收到 `references` 后断开 SSE，但服务端仍按正常对话流程写入用户消息。首次会话写入会同步调用 `ConversationServiceImpl.generateTitleFromQuestion()`，从而在检索开始前触发外部回答模型。
- 本地百炼账号返回 `Arrearage` 后模型路由仍会尝试降级，导致该额外调用污染首字与检索延迟；这不属于待评测的检索通道。

### 修复与验证

- `/rag/v3/chat` 增加默认关闭的 `retrievalOnly` 参数。开启时跳过会话记忆加载与写入，检索引用投影后仅发送 SSE `[DONE]`，不调用回答模型、不持久化空助手消息或生成标题。
- `StreamChatPipelineTest` 覆盖“有引用”和“空检索”两条路径，断言两者均不会调用记忆服务或 `LLMService`。
- 隔离实例上的 B 组图像样本已完成 HTTP 探针：单条请求 5.1 秒内返回 10 条引用；offset 5--9 的 5 条小批量全部返回引用，`no_retrieval=0`，报告为 `scripts/eval/report/industrial_eval_v2_B_retrieval-only_05_09.json`。

### 后续约束

- 正式 A/B/C/D 检索基线必须使用 `--retrieval-only`，并在报告的 `retrieval_options.retrievalOnly=true` 中保留可核验标记。
- 以上探针仅证明评测链路隔离和 B 组小批量稳定性；完整 25 条 B 组及 A/C/D 全量结果完成前，不得更新对外指标。

## 2026-08-06：文本向量通道偶发长尾（P0，待修复）

### 证据

- A 组 offset 30--39 的“热轧机齿轮箱润滑系统应如何维护”在客户端 20 秒预算内未收到引用。服务端随后记录向量全局检索已返回 30 个 Chunk，但该通道耗时 18184ms，客户端已断开 SSE。
- 该样本不是语义未命中；当前 `no_retrieval` 把“真实空结果”和“客户端超时”混在一起，不能用于判断检索质量。

### 当前处理

- 评测 Runner 增加每条 `latency_ms`、`retrieval_status` 以及汇总 P50/P95/max；状态至少区分 `received`、`timeout`、`empty_references`、`rejected` 和请求错误。
- 完整基线重跑将使用覆盖观测长尾的临时预算，并单独报告超时和 P95；这只保证质量评测不被误判，不代表性能问题已关闭。

### 后续修复方向

- 定位 `EmbeddingService.embed()` 的外部调用长尾及已取消意图任务的资源占用，补齐可取消/有界超时的检索依赖策略；修复后以原始性能预算重跑 A/B/C/D。

### 已完成的局部收敛

- Rerank 改用独立的 `rerankHttpClient`，`rag.rerank.timeout-millis` 默认 3000ms，禁用连接重试；失败仍由现有模型路由降级到 noop Rerank，或者由 `RerankPostProcessor` 返回预排序 Top-K。
- 配置单测验证 connect/write/read/call 四项预算均受该值约束。重启后的问题样本 HTTP 探针为 11390ms、成功返回 10 条引用；相对修复前的 41453ms 已明显收敛，但单样本不能代替 P95，且向量嵌入仍是主要剩余长尾。

### 意图分类隔离验证

- `retrievalOnly` 现在在关闭查询重写时直接构造空意图的单子问题，跳过意图分类、澄清与系统回答；全局向量、图像和超图通道仍使用原问题检索。正常对话路径不变。
- 流水线测试断言该模式不调用记忆、意图、澄清或回答模型。相同问题单条探针从 11390ms 降至 1265ms；相邻 5 条为 296--1985ms，全部收到 10 条引用。该结果作为小批量性能证据，完整 P95 仍待 A/B/C/D 重跑。

## 2026-08-06：已完成 A/B/C/D 通道基线

- A 文本基线（50 条）：`Hit@1/3/5=100%`、`MRR=1.000`、`P50=250ms`、`P95=2672ms`，全部为 `received`。文本样本未标注单一来源 ID，故 `source_id_hit_rate=0` 不代表检索失败。
- B 图像通道（25 条）：全部收到引用；图像通道与来源 ID 命中率均为 `88%`，图像语义答案 `Hit@3/5=80%`、`MRR=0.400`、`P95=7297ms`。该结果显示图像证据可用，但图纸语义排序与尾延迟仍需优化。
- C 超图通道（25 条）：超图通道命中率 `100%`，但严格关系答案 `Hit@1=36%`、`Hit@5=52%`、`MRR=0.398`、来源 ID 命中率 `8%`、`P95=5796ms`。这是真实的“关系候选能召回、关系路径/实体对齐不足”基线，后续优先做实体归一化、证据路径校验和有限多跳。
- D 全链路融合（100 条）：`Hit@1/3/5=34%/51%/59%`、`MRR=0.4493`、期望通道命中率 `76%`、`P50=3406ms`、`P95=13234ms`，全部为 `received`。分场景的 `Hit@5` 分别为事实 `52%`、口语 `56%`、图纸 `80%`、关系 `48%`。
- D 的图纸场景期望通道/来源 ID 命中率均为 `96%`，但 `Hit@1=4%`：图像候选已可用，当前多源融合和精排仍不能稳定将其推至首位。关系场景期望超图通道命中率 `96%`，而 `Hit@5=48%`，也印证关系路径对齐仍是后续重点。
- 原始批次及合并报告为 `scripts/eval/report/phase82c_final_{A,B,C,D}_*.json`。四组都使用 `retrievalOnly=true`、`enableRewrite=false` 和 `runtime.label=seeded-hyperedges-rate-limit-disabled`；它们是检索/融合基线，不能替代正常回答质量或查询重写 A/B 的结论。

## 2026-08-06：嵌入依赖预算隔离（P0 已修复，待全量回归）

- 根因：`SiliconFlowEmbeddingClient` 和 `OllamaEmbeddingClient` 复用通用 `syncHttpClient`，其 `callTimeout=45s` 且允许连接重试；单次嵌入长尾可以超过 SSE 客户端的检索预算。
- 修复：新增具名 `embeddingHttpClient`，由 `rag.embedding.timeout-millis`（默认 `5000ms`）统一约束 connect/write/read/call 四项超时，并禁用连接重试；两个 provider adapter 显式注入该 client，避免依赖参数名注入。
- 回归：`HttpClientConfigTest` 覆盖预算与重试策略，并以 Spring context 验证两个 adapter 都可通过 `embeddingHttpClient` seam 注入（3 项通过）。
- HTTP 探针：历史长尾的文本单通道样本在 `retrievalOnly=true`、`enableRewrite=false`、图像/超图关闭、12 秒客户端预算下，于 `1359ms` 收到 10 条 references。原始探针为 `scripts/eval/report/phase82d_embedding_budget_probe.json`，仅作运行时证据，不替换 A/B/C/D 基线。
- 后续：仍需在相同 20 秒预算下重跑足量 A/B/C/D，才可宣称 P95 的改善或将该结果用于简历指标。

## 2026-08-06：嵌入预算校准（8.2-E，已完成）

- 复现：在冷启动的图像+超图并行检索中，`5000ms` 单 provider 预算会使 SiliconFlow embedding 超时，随后本地 Ollama 不可用，导致候选耗尽并输出 `empty_references`。这说明 5 秒虽可约束长尾，却不足以覆盖当前多模态运行时的正常波动。
- 调整：默认 `rag.embedding.timeout-millis` 调整为 `10000ms`，仍保留独立 client、四项总预算和禁重试策略，避免回退到原 `45s` 通用客户端。
- 回归：Spring 注入测试同时断言默认 client 的 `callTimeout=10000ms`，确保配置默认值与两个 provider adapter 的实际注入一致。
- 边界：10 秒只是在可用性和尾延迟之间的当前折中；完整 P95 需在该配置稳定后重跑，不能用本次小批探针替代。

## 2026-08-06：本地词法 Rerank 降级（8.2-F，已完成）

- 根因：运行时日志确认 BaiLian Rerank 返回 HTTP 400（`Arrearage`），模型路由会降级到 `rerank-noop`。原实现只按融合后的既有顺序截断，外部 Rerank 故障时无法利用 query 对候选重新排序。
- 调整：最终 fallback 改为确定性的本地词法重排。它将 query 和候选文本归一化为 Unicode 字母/数字 bigram，使用 Dice 相似度并保留小比例的既有融合分；相同分数按原顺序稳定排序。输出复制候选并标记 `rerankMode=local_lexical_fallback`，以便诊断引用来源。
- 回归：`NoopRerankClientTest` 覆盖“query 对齐候选覆盖更高先验的无关候选”及同分稳定顺序（2 项通过）；`RerankPostProcessorTest` 的远程重排异常回退行为也保持通过（2 项）。
- 运行时证据：10 秒 embedding 预算下，日志确认实际走过远程 Rerank 失败到本地 fallback；图纸首批 5 条探针均为 `received`，Hit@1/3/5=80%/80%/80%，MRR=0.80，P50/P95=2828/5875ms。原始报告为 `scripts/eval/report/phase82f_local_rerank_image_00_10s.json`（单条探针为 `phase82f_local_rerank_image_10s_probe.json`）。
- 边界：历史 8.2-C D 的同类首批结果运行于不同配置，不能与此报告构成严格 A/B，也不能据此写入简历效果数字；待固定 10 秒 embedding 预算后完成同样本、同服务参数的完整重跑。
