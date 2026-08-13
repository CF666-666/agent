# R3-C 查询重写严格配对 A/B — 结论文档

> 闭环：R3-C（查询重写开/关严格 A/B）
> 日期：2026-08-13
> 状态：✅ 完成（含索引根因修复 + 冻结集真实运行）
> 独立复审：无 P0；P1 已在本文档披露；P2 记录于文末

---

## 0. 前置根因修复（本闭环的阻塞解除）

R3-C 冻结集此前几乎全部检索失败（Hit@1≈0.03~0.23），探针定位根因：

- **不是 embedding 模型漂移**：Milvus 存储向量与当前 SiliconFlow `Qwen/Qwen3-Embedding-8B` 对同一文本的余弦相似度为 0.9999~1.0000（8 条抽查），索引确为当前模型构建。
- **不是查询重写问题**：`rag_default_store` 中 FAQ 仅 **77/210 条**（steel 66 / petro 7 / power 0），而冻结集（会话 10 组 + 噪声 20 条）全部以 `power_energy` 为知识来源 → 电力 FAQ 一条未入库，检索必然失败。
- **修复**：以 `PHASE5_INGEST=true` 重建后端容器，启动时按 `phase5.startup-embedding.model-id=qwen-emb-8b` 全量入库 210 条 FAQ + 12 张图像描述。日志确认 `input=210, embedded=210, persisted=210`。重建后凝汽器问题 top-1 相似度 0.92（此前 0.49 且 top-5 全为他行业）。

## 1. A/B 设计（严格配对）

配对校验器 `compare_rewrite_ab.py` 强制 off/on 两份报告除 `enableRewrite` 外全部一致：

| 配对维度 | off / on 取值 |
|---|---|
| 数据集 | `industrial_conversation_r3_frozen.jsonl` sha256=`25fc72f9...dc1` |
| 通道开关 | `enableImage=false`、`enableHyperGraph=false`、`enableFusion=true`、`retrievalOnly=false` |
| 运行条件 | `request_timeout=90s`、`warmup_count=1`、warmup query 相同 |
| 后端镜像 | `sha256:9ddd3438c6f3e2ea33ec6604c5fa9d453218edc74f25c916a327577636a71059`（不可变，两轮一致） |
| 执行指纹 | `dae6c30f9f3ba27fdab28f1fb644f51e8bb1bb40751e54c76e5f4ac373199510`（两轮一致） |
| case 顺序 | frozen-001 → 010，逐条 `(dataset_id, target_query)` 一致 |

## 2. 结果

### 2.1 多轮会话集（10 组，指代/省略难例）

| 指标 | rewrite-off | rewrite-on | 配对差 |
|---|---:|---:|---:|
| 执行成功率 | 10/10 (100%) | 9/10 (90%) | on 1 条目标轮超时 |
| Hit@1 | 0.50 | 0.8889（成功样本） | **+0.4444**（公共 9 条） |
| MRR | 0.5333（全量 10） | 0.8889（成功样本） | **+0.4074**（公共 9 条，相对 +84.6%） |
| 目标轮 P50 / 平均延迟 | 约 26s / 29s | 约 40s / 44s | on 显著更高 |

公共配对质量样本 **9 条**。逐条：rewrite 挽回 4 条（002/003/004/005：off MRR=0 → on MRR=1.0）、1 条部分提升（010：0.333 → 1.0）、3 条持平（006/007/009：双方 1.0）、**1 条回退（008：off 1.0 → on 0.0，rewrite 改写破坏）**。

### 2.2 单轮噪声集（20 条，错词/同音/省略/语序难例）

| 指标 | rewrite-off | rewrite-on |
|---|---:|---:|
| 执行成功率 | 20/20 (100%) | 20/20 (100%) |
| Hit@1 | 0.00% | **45%** |
| MRR | 0.0000 | **0.4500** |

两轮均关闭图像/超图通道（与 2.1 同配置），`--retrieval-only` 运行；配置经手工核对一致，作为观察性配对（无自动校验器覆盖，见 P2-1）。

## 3. 限制与披露（对应独立复审 P1）

1. **样本量小**：会话集公共配对仅 9 条，单条案例对 delta 影响大；`mrr_relative=+84.6%` 为 9 条小样本结果，应视为"显著提升方向"而非精确效应量。
2. **被排除案例偏向 on**：on 超时被排除的 `r3-conversation-frozen-001` 在 off 下是**命中案例**（MRR=1.0）。其 on 目标轮 `timed_out`（latency=102s > 90s 客户端预算）。按配对统计标准排除执行失败样本正确，但需明示：**若按 on 失败计 0 分，delta 会缩小**。
3. **rewrite 稳定性/延迟代价**：on 执行成功率 90% vs off 100%；on 目标轮延迟显著更高（P50 约 40s vs 26s，平均 44s vs 29s）。001 根因：rewrite-on 每轮端到端延迟更高（重写 LLM + 意图 + 答案生成叠加），多轮累计后目标轮突破 90s 预算。结论表述必须同时披露收益与代价。
4. **命中口径**：Hit/MRR 基于 references snippet 与 golden 文本匹配；`source_id_hit_rate=0` 因 FAQ chunk 的 `extra.sourceId` 为 chunk UUID、golden 为 `power_energy` 来源文档 ID，二者契约不对齐（数据集契约事实，非缺陷）。不得把来源命中率表述为 0% 的检索失败。

## 4. 结论（限制性表述）

> 在冻结的多轮指代/省略会话集（10 组，电力知识来源）上，查询重写在**公共配对 9 条**上使 Hit@1 从 0.50 提升至 1.00（+0.4444）、MRR 从 0.4815 提升至 0.8889（+0.4074，相对 +84.6%）；在 20 条单轮噪声难例上使 Hit@1 从 0% 提升至 45%。代价是端到端延迟显著上升（目标轮 P50 约 26s→40s）与执行成功率下降（100%→90%）。**以上数字仅代表 frozen 集小样本观察，未在更大主集上外推。**

## 5. 复现命令

```bash
# 索引重建（一次性，幂等）
cd ragent && $env:PHASE5_INGEST="true"; $env:RAG_RATE_LIMIT_GLOBAL_ENABLED="false"; docker compose up -d --no-deps backend

# 会话集 A/B
python scripts/eval/conversation_eval.py --dataset scripts/eval/datasets/industrial_conversation_r3_frozen.jsonl --out scripts/eval/report/r3c_20260813_valid/rewrite_off.json --disable-rewrite --request-timeout 90
python scripts/eval/conversation_eval.py --dataset scripts/eval/datasets/industrial_conversation_r3_frozen.jsonl --out scripts/eval/report/r3c_20260813_valid/rewrite_on.json --request-timeout 90
python scripts/eval/compare_rewrite_ab.py --rewrite-off scripts/eval/report/r3c_20260813_valid/rewrite_off.json --rewrite-on scripts/eval/report/r3c_20260813_valid/rewrite_on.json --out scripts/eval/report/r3c_20260813_valid/compare.json

# 噪声集观察（retrieval-only + 关闭图像/超图）
python scripts/eval/retrieval_eval.py --dataset scripts/eval/datasets/industrial_noise_r3_frozen.jsonl --out scripts/eval/report/r3c_20260813_valid/noise_rewrite_off.json --disable-rewrite --disable-image --disable-hypergraph --retrieval-only --warmup-count 2 --request-timeout 30
python scripts/eval/retrieval_eval.py --dataset scripts/eval/datasets/industrial_noise_r3_frozen.jsonl --out scripts/eval/report/r3c_20260813_valid/noise_rewrite_on.json --disable-image --disable-hypergraph --retrieval-only --warmup-count 2 --request-timeout 30
```

## 6. 独立复审问题清单

| 级别 | 问题 | 处理 |
|---|---|---|
| P0 | 无 | — |
| P1 | 小样本/被排除案例披露不足 | 本文档 §3.1/§3.2 披露 |
| P1 | on 目标轮超时根因未分析 | 本文档 §3.3 披露并定位（延迟累积超 90s 预算） |
| P2 | noise 报告无自动配对校验器 | 已手工核对配置一致；后续可在 R5 补通用校验器 |
| P2 | source_id_hit_rate=0 未解释 | 本文档 §3.4 披露口径 |
| P2 | compare.json 并存 summary 全量与公共配对两套 MRR 口径 | delta 一律以公共配对样本（§2.1 标注）为准 |

## 7. 下一步

R3-D：错误改写分析（正确补全/无效改写/错误实体注入/应澄清未澄清）。案例 008（off 命中、on 回退）与 frozen 集改写后 query 的审计是核心素材。
