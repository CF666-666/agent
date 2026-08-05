# Phase 8.2-C 评测阻塞与修复记录

> 状态：进行中。本文只记录已经由本地运行时或原始报告确认的事实；未完成修复和未重跑的结果不得用于简历、README 或对外指标。

## 当前边界

- 已完成：阶段 2B 的受控 Demo 超边导入。以 `phase5.import-demo-hyperedges=true` 启动时，`data/hypergraph/hyperedges.jsonl` 的 603 条超边已写入 PostgreSQL，并由运行时加载器载入内存图。
- 已完成：请求级检索开关、分层数据集、分批报告、case 级防重和运行时元数据能力。
- 未完成：阶段 8.2-C 的 A（文本）、B（图像）、C（超图）、D（全链路）四组隔离环境重跑与正式结论。

## 已确认问题

### P0：图像检索在隔离运行时仍有首个 references 长尾

**症状**

- 在启动参数 `phase5.import-demo-hyperedges=true` 且 `rag.rate-limit.global.enabled=false` 的本地服务上，B 图像组首个 5 条批次（请求超时 12 秒）出现 3 条未收到 `references` 事件。
- 对应原始诊断报告：`scripts/eval/report/industrial_eval_v2_B_isolated_00.json`。该文件不是正式基线，不得纳入统计结论。

**已排除因素**

- 不由全局聊天队列排队引起：该次运行已关闭 `rag.rate-limit.global.enabled`。
- 不由空超图引起：服务启动日志确认导入并加载 603 条超边；图像组同时关闭了超图通道。
- 不应再由外部 Rerank 余额不足直接中断：`RerankPostProcessor` 已在 Rerank 运行时异常时降级返回原候选 Top-K（提交 `5e958bb`）。

**待验证假设（按优先级）**

1. `ImageSearchChannel` 或图像向量库查询在特定图像语义 query 上存在慢查询/阻塞。
2. 图像 embedding 或上游模型调用存在超过 12 秒的偶发长尾。
3. 图像检索完成后，后处理或 SSE references 投影发生阻塞/异常。

**下一闭环**

1. 建立单条图像 query 的 HTTP 重现脚本，断言在阈值内收到非空 `references`，并输出分段耗时（图像向量查询、后处理、SSE 投影）。
2. 用同一 query 分别关闭图像、仅启用图像、开启全链路，定位长尾边界。
3. 只对已定位边界做最小修复；补充回归测试及 HTTP 重跑证据。

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
