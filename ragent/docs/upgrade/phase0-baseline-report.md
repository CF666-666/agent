# Phase 0 真实检索基线报告

## 评测条件

- 数据集：`scripts/eval/datasets/industrial_eval_v2.jsonl`，共 100 条，fact、colloquial、image、relation 各 25 条。
- 评测服务：本次新代码打包后的 JAR，隔离运行在 `9092`；未使用旧的 `9090` 容器结果。
- 检索设置：查询重写关闭；仅改变图像、超图、多源融合开关。
- 评价方式：自动校验检索片段、期望通道类型和 `sourceId`；`human_evaluation=false`，没有人工打分。
- 运行日志：`local-baseline.log` 仅作为本地临时运行日志，不作为业务数据来源。

## 结果

| 配置 | 样本范围 | Hit@1 | Hit@3 | Hit@5 | MRR | 期望通道命中 | 来源 ID 命中 |
|---|---:|---:|---:|---:|---:|---:|---:|
| A：文本/意图基线 | fact + colloquial，50 | 98% | 98% | 98% | 0.980 | 98% | 不适用 |
| B：图像通道 | image，25 | 96% | 96% | 96% | 0.960 | 96% | 96% |
| C：超图通道 | relation，25 | 76% | 96% | 96% | 0.860 | 96% | 92% |
| D：全链路 | 全部，100 | 93% | 98% | 98% | 0.955 | 99% | 48%* |

\* D 的总体来源 ID 指标包含文本场景。当前 FAQ 文本元数据仍使用历史文档标识，不能与 v2 数据集中的逻辑 `source_doc` 一一对应；图像和关系场景的来源 ID 校验可直接使用，分别为 96% 和 96%。

## 结果文件

- `scripts/eval/report/industrial_eval_v2_A.json`
- `scripts/eval/report/industrial_eval_v2_B.json`
- `scripts/eval/report/industrial_eval_v2_C.json`
- `scripts/eval/report/industrial_eval_v2_D.json`

D 报告由以下四个场景批次合并生成：`D_fact`、`D_colloquial`、`D_image`、`D_relation`。合并脚本为 `scripts/eval/merge_eval_reports.py`。

## 当前解释

- 文本/意图链路在本批 FAQ 与口语样本上已经形成较高基线，后续不能只用 Hit@1 继续包装普通 RAG 增益。
- 超图场景的主要问题不是“通道完全失效”，而是部分关系路径排序或结构化片段与黄金答案不一致：期望通道命中 96%，但 Hit@1 为 76%。后续优先优化实体归一化、候选边排序和路径证据。
- 图像通道已经具备可验证的原始图像来源 ID；后续可以进一步增加 Recall@5、图像引用完整率和 URL 可访问性指标。
