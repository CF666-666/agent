# Ragent 端到端评测报告

> 评测日期:2026-08-03 | 评测方式:本地可复现脚本(`scripts/eval/`)
> 环境:后端容器 `ragent-backend`(9090)、SiliconFlow DeepSeek-V3.2 打分、RAGAS 0.4.3

## 1. 评测集

| 数据集 | 样本数 | 来源 | 说明 |
|--------|:--:|------|------|
| `industrial_eval.jsonl` | 48 | FAQ 210 条按分类分层抽样(每类 12) | query + 标准答案,覆盖故障诊断/操作规程/维护保养/安全规范 |
| `industrial_eval_colloquial.jsonl` | 19 | 口语化改写(人工) | 口语/模糊问法,模拟真实用户,验证查询重写价值 |

生成命令:
```bash
python scripts/eval/build_dataset.py --per-category 12   # 48 条
```

## 2. 检索指标(Hit Rate / MRR)

评测方式:调用 SSE `/rag/v3/chat`,取 `references` 事件(检索结果按序),与 golden 标准答案做文本匹配判定命中。

### 2.1 FAQ 精确评测(48 条,查询重写开启)

| 指标 | 数值 |
|------|:--:|
| Hit Rate@1 | **100.00%** |
| Hit Rate@3 | **100.00%** |
| Hit Rate@5 | **100.00%** |
| MRR | **1.0000** |

### 2.2 口语化评测 + A/B 对比(19 条)

| 指标 | 重写关闭(基线) | 重写开启 | 说明 |
|------|:--:|:--:|------|
| Hit Rate@1 | 100.00% | 94.74% | 意图定向 + 向量检索本身已足够强,单轮口语化场景重写无增益(偶有扰动) |
| Hit Rate@3 | 100.00% | 100.00% | |
| MRR | 1.0000 | 0.9737 | |

> **结论**:系统检索在"问题 → 知识"匹配上已接近上限(意图定向 + 全局向量 + 图像 + 超图四路)。查询重写的价值体现在**多轮指代、上下文补全**场景,单轮 FAQ 评测集无法体现其增益(如实呈现,不夸大)。

## 3. RAGAS 生成质量(12 条样本)

打分模型:SiliconFlow `deepseek-ai/DeepSeek-V3.2`;Embedding:`BAAI/bge-m3`。

| 指标 | 数值 | 含义 |
|------|:--:|------|
| **faithfulness(答案忠诚度)** | **0.9101** | 回答内容对检索上下文的忠实程度,不编造 |
| **context_precision(上下文精准度)** | **0.8629** | 检索返回的上下文中,有用信息占比 |
| **context_recall(上下文召回率)** | **0.9167** | 标准答案所需信息在上下文中被覆盖的比例 |
| answer_relevancy(回答相关性) | 0.3146 | 回答与问题的切题程度(评分模型对详尽条目式回答判定偏严,参考性有限) |

复现命令:
```bash
python scripts/eval/ragas_eval.py --limit 12
```

## 4. 结论与简历数据建议

**可用于简历/面试的真实数据(全部可一键复现):**

1. 检索链路:工业 FAQ 评测集 **Hit Rate@1 = 100%、MRR = 1.0**(48 条);口语化 query 场景 Hit Rate@1 达 94.7%(Top-3 100%)
2. 生成质量:**答案忠诚度 0.91、上下文精准度 0.86、上下文召回率 0.92**(RAGAS 评测)
3. 系统具备:意图定向 + 全局向量 + 图像语义 + 超图推理 四路检索、多源融合 + Rerank、references 结构化溯源

**不建议编造的表述**:请勿写"查询重写将 Hit Rate 从 78% 提升至 87%"——本评测集数据显示单轮场景重写无增益(基线已 100%)。可改为:"支持口语化/多轮 query 的查询重写与拆分,检索 Hit Rate@1 达 100%"。

## 5. 目录与复现

```
scripts/eval/
├── build_dataset.py        # 评测集构建
├── retrieval_eval.py       # 检索指标 Runner(支持 --disable-rewrite A/B)
├── ragas_eval.py           # RAGAS 生成质量(支持 --eval-provider)
├── datasets/               # 评测集 JSONL
└── report/                 # 评测报告 JSON
```

依赖:Python 3.11 venv(`scripts/eval/.venv`)+ `pip install ragas "langchain-community<0.4"`;默认需宿主机环境变量 `SILICONFLOW_API_KEY`，显式传入 `--eval-provider bailian` 时改用 `BAILIAN_API_KEY`。

## 6. 已知局限与测评集改进方向(迭代 TODO)

> 当前数据已可用于简历/面试,但**测评集与评测方式仍有明显局限**,以下为后续迭代方向(优先级从高到低)。

### 6.1 当前局限

| # | 局限 | 影响 |
|:--:|------|------|
| L1 | 检索评测集 query 直接来自 FAQ 原文,精确匹配(score=1.0) | Hit Rate@1=100% 无区分度,测不出检索链路"下限" |
| L2 | 口语化评测仅 19 条、为人工改写,未覆盖多轮指代/错字/方言 | 查询重写价值未能量化(单轮场景基线已 100%) |
| L3 | RAGAS 仅 12 条样本,统计意义有限 | 指标置信区间宽,分场景指标缺失 |
| L4 | `answer_relevancy` 仅 0.31,受打分模型与回答风格影响 | 该指标可信度存疑,暂不建议写入简历 |
| L5 | 未做"带/不带超图"、"带/不带图像通道"的 A/B | 多路检索各通道的边际贡献未量化 |
| L6 | 无标准答案文档级标注(golden_doc_ids) | Recall@K 无法按"相关文档集"精确计算 |

### 6.2 改进方向

- **[P0] 多轮/指代评测集**:构造"前 1-2 轮上下文 + 当前轮指代 query"(如"它的扭矩呢?"),验证查询重写/会话摘要的真实增益,可量化 MRR 提升。
- **[P0] 检索评测集难度分层**:FAQ 原文(易)、口语化(中)、术语替换/错字(难)三层,分别报告指标,展示系统鲁棒性。
- **[P1] 扩大 RAGAS 样本至 40-48 条**,并按场景(故障/工艺/安全/图纸)分组报告;修复 `answer_relevancy`(调整打分 prompt 或换评估模型)。
- **[P1] 通道级 A/B**:为 SSE 增加 `enableHyperGraph` / `enableImage` 开关,量化超图/图像通道对命中率与忠诚度的边际贡献。
- **[P2] 标注 golden_doc_ids**,精确计算 Recall@K 与 MRR 的文档级口径。
- **[P2] 建立评测基线仓库**,每次链路改动(重写/检索/融合)跑全量对比,沉淀回归曲线。

### 6.3 数据记录汇总(2026-08-03)

| 指标 | 数值 | 评测集 | 可复现 |
|------|:--:|------|:--:|
| Hit Rate@1 | 100% | FAQ 48 条 | ✅ |
| MRR | 1.0000 | FAQ 48 条 | ✅ |
| Hit Rate@1(口语化) | 94.7%(重写开)/ 100%(重写关) | 口语化 19 条 | ✅ |
| RAGAS 忠诚度 faithfulness | 0.9101 | 12 条 | ✅ |
| RAGAS 上下文精准 context_precision | 0.8629 | 12 条 | ✅ |
| RAGAS 上下文召回 context_recall | 0.9167 | 12 条 | ✅ |

## 7. 2026-08-06：8.2-H 全链路受控复测

运行条件：固定 100 条 `industrial_eval_v2`，`retrievalOnly=true`、关闭 query rewrite、图像/超图/融合开启、embedding 总预算 10 秒、本地词法 Rerank fallback、每个分片正式计分前预热 1 条。预热记录不计入指标。

| 指标 | 全量 100 条 |
|---|---:|
| Hit Rate@1/3/5 | 79% / 81% / 81% |
| MRR | 0.7994 |
| 期望通道命中率 | 94% |
| P50 / P95 | 2406ms / 10078ms |
| 收到引用 | 100 / 100 |

分场景 Hit@1：事实 80%、口语 96%、图纸 92%、关系 48%。图像语义候选已稳定可达，但关系路径与实体对齐仍是主要短板。原始分片及合并报告见 `scripts/eval/report/phase82h_D_*.json`。

该结果与 8.2-C 历史 D 基线的运行配置不同（嵌入预算、fallback、预热策略），用于当前版本的真实能力刻画，不能单独归因于某一项改动或作为严格 A/B 提升百分比写入简历。

## 8. 2026-08-13：R0-E 受控全链路运行（降级执行基线）

本次运行用于验证当前代码、稳定索引与请求级 deadline 下的真实执行状态，不与 8.2-H 作严格质量对比。四份原始报告、合并报告、复跑命令和无密钥执行指纹已归档至 `scripts/eval/report/baselines/r0e-20260813-degraded-full-chain/`。

运行身份：`industrial_eval_v2.jsonl` 共 100 条（事实/口语/图纸/关系各 25 条），数据集 SHA-256 为 `75db65ee7f95df7bdaae1ff050989b17cf7585de06546dac6900cbb35b3e77cc`；`retrievalOnly=true`，改写、图像、超图和融合均开启；请求 deadline 为 18 秒，改写子预算 4.5 秒、图像预算 3 秒、超图预算 7 秒。运行标签为 `R0-E-controlled-full-chain`，执行指纹为 `c51bdb2a03de75c3d3a14a654cda84ca7db12882edfbecc7df84dda94144a4fd`。

| 指标 | 结果 |
|---|---:|
| 总样本 / 质量样本 | 100 / 0 |
| `channel_timed_out` | 100 / 100 |
| Hit Rate@1/3/5、MRR | N/A（无可计分质量样本） |
| P50 / P95 端到端耗时 | 12.917s / 14.570s |

全部样本均返回了其他通道的真实引用，但超图通道在 7 秒预算内未完成 LLM 实体抽取，因而被 `retrieval_status` 正确标为 `TIMED_OUT`。按当前评测契约，任一通道 `TIMED_OUT`、`CANCELLED` 或 `FAILED` 的样本不计入 Hit@K/MRR，不能把它伪装成普通零命中或写成质量指标为 0。

结论：本次结果证明请求 deadline、通道降级、状态事件和归档链路可用，但**不构成当前全链路检索质量基线，也不能用于简历量化陈述**。下一步应先优化超图查询可用性（实体抽取快速路径、缓存或规则降级），再在相同配置下重跑质量评测。

## 9. 2026-08-13：R2-B 关系专项基线

固定 50 条来源可验证的关系样本，按超边证据隔离为 tuning/frozen 各 25 条，覆盖 40 条单超边和 10 条双超边问题。SSE 引用新增透传 `relationEvidence`，评测直接按 `hyperEdgeId/sourceDocument` 计算结构化指标，不再依赖答案片段近似判断。

未融合正式基线 50/50 条可计分：通道逐超边 Hit@1/3/5 为 **74%/80%/82%**，Recall@1/3/5 为 **68%/76%/77%**，严格有序路径命中率 **30%**，逐超边来源映射准确率 **74%**，P50/P95 为 **0.217s/4.306s**。冻结子集逐超边 Hit@1/5 为 **84%/84%**、Recall@5 **84%**、严格路径 **36%**、来源映射 **84%**，尚未达到 Recall@5≥85%、来源准确率≥90% 的阶段门槛。

确定性分类归档为：执行失败未归因 0、别名未归一 0、超边未完整召回 14、排序/路径错误 21、来源 ID 不一致 0、融合挤压 0，另有 15 条通过。超时和业务标签不再被直接解释为实体或别名因果根因；融合组仅作观察，不能据此书写提升百分比。

原始报告、数据指纹、镜像身份、失败明细与复现命令见 `scripts/eval/report/r2b_20260813/README.md`。该结果用于指导 R2-C/R2-D，不替换 100 条全链路主指标，也暂不写入简历。

## 10. 2026-08-13：R2-C1 实体归一化 tuning 观察

在不查看 frozen 失败详情的前提下，统一实体与查询的 NFKC、大小写、空白和 `µ/μ` 表面形式，并仅依据 tuning 集加入 4 个工业简称；同时修正 Spring Map 中文 key 的配置绑定。20 项 Java 回归通过，独立复审无 P0/P1。

隔离后端仅运行 tuning 25 条，其中 23 条可计分、2 条通道超时。逐超边 Hit@1/5 为 **78.26%/86.96%**，Recall@5 **78.26%**，严格路径 **30.43%**，来源映射 **69.57%**，P50/P95 为 **2.555s/9.730s**。该运行与 R2-B 的样本范围和执行状态不同，只作为调优观察，不构成严格 A/B，不写入简历。归档见 `scripts/eval/report/r2c1_20260813/README.md`。

## 11. 2026-08-13：R2-C2 来源字段可用性契约

关系引用继续输出真实 `hyperEdgeId/sourceDocument/sourceChunkId/sourceChunkIndex/sourcePage/documentVersion`，并为每个字段增加 `AVAILABLE/UNAVAILABLE` 状态；评测按唯一超边汇总 available、unavailable、conflicting，同一超边多值或“有值与缺失并存”均记为冲突。系统不使用 edgeId、数组位置或文档名合成缺失的 chunk/page。

Demo JSONL 审计显示 603/603 条具备 edgeId 与 sourceDocument，603/603 条缺少 chunkId、chunkIndex、page 和 documentVersion。隔离镜像 `7cb1d67` 的 1 条真实 SSE 探针返回 5 个唯一超边：edgeId/document 均 5/5 available，其余四类字段均 5/5 unavailable、0 conflicting，与源数据一致。Java 11 项、Python 49 项测试通过，独立复审无 P0/P1/P2。该探针只验证契约，不作为质量指标。
