# R2-B 关系检索基线与失败分类

## 运行身份

- 日期：2026-08-13
- 后端提交：`2234c25eb5c13a7e10c06b853a0cfc618505e116`
- 隔离后端镜像：`sha256:0e0a04a4d4068379c4c88c0f8b48c5ba55847541185e2e9b8e30bf6a4055811c`
- 数据集：`industrial_relation_r2.jsonl`，50 条，SHA-256 `334ad4149aae457f282d4d6f226527c557c2c27a9f13c4f25f1039759d8e213d`
- 数据划分：调优 25 条、冻结 25 条；超边证据不交叉
- 路径分层：单超边 40 条、双超边 10 条
- 评测配置：关闭查询重写和图像，`retrievalOnly=true`，请求超时 15 秒，预热 2 条不计分
- 运行档案：SiliconFlow `Qwen3-Embedding-8B`、`Qwen3-Reranker-8B`，执行指纹 `7832aa1b210b3c128078e0bb199d7c00602617e01b1ab1f9f221728620773ba6`

后端镜像从隔离 worktree 构建，未包含主工作区的其他未提交改动。评测脚本在运行时继续增加了结果审计字段，因此报告中的 Git 状态为 dirty；数据集、运行器、配置和模型均有独立哈希，最终脚本随本闭环提交。

## 结果

### 未融合组（正式基线）

原始报告：`unfused-final.json`；失败分类：`unfused-failures.json`。

| 指标 | 结果 |
|---|---:|
| 总样本 / 质量样本 / 执行排除 | 50 / 50 / 0 |
| 通道逐超边 Hit@1 / @3 / @5 | 74% / 80% / 82% |
| 通道逐超边 Recall@1 / @3 / @5 | 68% / 76% / 77% |
| 严格有序路径命中率 | 30% |
| 逐超边来源映射准确率 | 74% |
| 最终引用 Hit@1 / @3 / @5 | 76% / 82% / 82% |
| P50 / P95 | 0.217s / 4.306s |

冻结子集 25/25 条均可计分：逐超边 Hit@1/5 为 84%/84%，Recall@1/3/5 为 76%/84%/84%，严格有序路径命中率 36%，逐超边来源映射准确率 84%。该结果是 R2-C/R2-D 的对照基线，不是阶段最终验收结果。

### 融合开启组（观察值）

原始报告：`fused-final.json`；失败分类：`fused-failures.json`。

融合组 46/50 条可计分，4 条执行失败未归因；有效样本的通道逐超边 Hit@1/5 为 78.26%/82.61%，Recall@5 为 78.26%，严格路径命中率 30.43%，来源映射准确率 73.91%，最终引用 Hit@1/5 为 78.26%/82.61%，P50/P95 为 0.205s/10.033s。两组虽有相同数据与配置指纹，但质量样本集合和在线模型延迟不同；本闭环将其作为观察值，不声明严格 A/B 提升或下降。

当前 references 中 HYPERGRAPH 相对次序未被文本引用挤压，分类器记录 `fusion_crowd_out=0`。这不证明融合永远无挤压，只表示本次可计分样本未观察到该根因。

## 六类根因

未融合正式基线：

| 根因 | 数量 | 说明 |
|---|---:|---|
| 执行失败（未归因） | 0 | 仅记录执行事实，不从超时反推实体或别名根因 |
| 别名未归一 | 0 | alias 仅作诊断标签，不能仅凭 miss 确定因果 |
| 超边未完整召回 | 14 | 逐超边 Top-5 未覆盖全部 golden 超边 |
| 排序错误 | 21 | 完整召回后，Top-1、严格有序路径或最终引用位置不满足 |
| 来源 ID 不一致 | 0 | 完整召回的样本中未观察到独立的来源映射错误 |
| 融合挤压 | 0 | 本次最终引用排序未把通道 Hit@1 挤出 Top-1 |
| 通过 | 15 | 其余条件均满足 |

失败分类是确定性规则输出，不含人工评分。优先级固定为：执行失败未归因 → Top-5 未完整召回 → 来源不一致 → 路径/排序 → 融合挤压 → 通过；业务标签只用于切片，不直接充当根因。

## 结论与下一步

R2-A 已把 R0-E 的 100/100 超图超时恢复为 50/50 可计分，但关系质量尚未达到阶段门槛：冻结集 Recall@5 84% < 85%，来源准确率 84% < 90%，严格路径命中率仅 36%。

R2-C 应只使用 tuning 集处理实体覆盖、简称/编号/单位变体和来源映射；不得查看 frozen 失败详情进行调参。R2-D 再处理双超边路径评分，并在健康模型状态下重启隔离容器，只运行一次 frozen 最终验收。

复现命令：

```powershell
python -B scripts/eval/build_relation_dataset.py --check
python -u -B scripts/eval/retrieval_eval.py --dataset scripts/eval/datasets/industrial_relation_r2.jsonl --scenes relation --disable-rewrite --disable-image --disable-fusion --retrieval-only --request-timeout 15 --warmup-count 2 --runtime-label r2b-2234c25-isolated-v3 --label R2B-unfused-final-v3 --out scripts/eval/report/r2b_20260813/unfused-final.json
python -B scripts/eval/classify_relation_failures.py --report scripts/eval/report/r2b_20260813/unfused-final.json --out scripts/eval/report/r2b_20260813/unfused-failures.json
```
