# R0-E 执行可靠性修复记录

## 目的

在启动正式四场景评测前，修复请求级 deadline、SSE 异步入口和查询重写的预算竞争问题，确保评测脚本拿到的 `retrieval_status` 可以作为运行质量证据。

## 本闭环变更

- `/rag/v3/chat` 创建的 18 秒请求级 `RetrievalExecutionContext` 透传至实际被 Controller 调用的六参数 `streamChat` 入口；限流切面也绑定到该入口，避免同步处理时占住请求幂等锁。
- `retrieval_status.budgetMillis` 改为请求级预算，而非从通道状态反推，保证没有通道级预算时仍可观测到 18 秒总预算。
- 查询重写使用可配置的 4.5 秒子预算。子预算耗尽会取消该次 LLM 调用并回退到归一化原问题；父请求仍保持有效，继续执行后续检索。
- 通道超时状态的 `elapsedMillis` 封顶为自身预算，避免按通道优先级收集结果时把前序等待时间误记到后序超时通道。
- 评测脚本将任一通道的 `TIMED_OUT`、`CANCELLED`、`FAILED` 单列为执行状态并排除质量分母；它们仍保留在报告的状态分布中，不能伪装成正常零命中。
- Compose 的 `PHASE5_INGEST` 改为可配置。受控评测使用 `PHASE5_INGEST=false` 复用已验证索引，禁止启动时重建索引干扰评测。

## 验收证据

- 定向 Maven 测试：`RAGChatControllerTest`、`RetrievalExecutionContextTest`、`MultiChannelRetrievalEngineTest`，共 9 项通过。
- 真实 HTTP 全链路探针：请求在 11.7 秒内返回真实引用；请求级预算为 18,000ms，超图通道在 7,000ms 以 `TIMED_OUT` 降级，未写入伪造引用。
- 四条事实场景小批探针均返回 `retrieval_status`，且运行期间 `PHASE5_INGEST=false`。

## 评测运行约束

正式 R0-E 评测必须满足以下条件：

1. `PHASE5_INGEST=false`，以避免远程 embedding 重建与索引变更。
2. 每份报告记录相同的数据集指纹、运行标签、运行配置指纹和应用配置指纹。
3. `TIMED_OUT`、`CANCELLED`、`FAILED` 只计入执行状态分布，不进入 Hit@K 或 MRR 的质量分母。
4. `r0e_20260813_deadline/fact.json` 是修复前产生的无效产物；不得合并或引用。小批 `probe-*` 仅用于连通性验证，也不得作为正式指标。

## 后续

本闭环关闭后，重新执行四个场景的正式 100 条评测并合并报告；未完成正式报告前，R0-E 仍不能标记为完成。
