# R5-D RAGAS 生成质量评测 — 结论文档

> 闭环：R5-D（60 条 RAGAS 生成质量评测）
> 日期：2026-08-15
> 状态：✅ 完成（四层各 15 条）
> 报告：`ragas_report_final.json`

---

## 1. 结果

### 整体（60 条）

| 指标 | mean | 95% CI 半宽 |
|---|---:|---:|
| faithfulness 忠实度 | **0.614** | ±0.090 |
| context_precision 上下文精准度 | **0.627** | ±0.122 |
| context_recall 上下文召回 | **0.672** | ±0.127 |
| answer_relevancy 答案相关性（内部指标） | 0.420 | ±0.064 |

### 分层（各 15 条）

| 层 | faithfulness | context_precision | context_recall |
|---|---:|---:|---:|
| text（fact） | 0.736 | 0.780 | 0.769 |
| noise | 0.610 | 0.771 | 0.769 |
| image | 0.650 | **0.274** | **0.354** |
| relation | **0.465** | 0.699 | 0.800 |

### 观察（限制性）

1. **text 层最好**（faithfulness 0.736 / context_precision 0.780 / context_recall 0.769）：fact 问答检索质量与生成忠实度均最优，与 R5-B text 场景 Hit@1=86.67% 一致。
2. **image 层上下文精准度最差**（context_precision 0.274 / context_recall 0.354）：图像检索的上下文与 golden 的语义距离大，与 R4-D 图像通道 Hit@1=34% 的结论一致——是**检索瓶颈**，非生成缺陷。
3. **relation 层忠实度最弱**（faithfulness 0.465）：超图关系问答的生成内容与检索上下文一致性低，是**生成瓶颈**（R2 超图路径的 LLM 兜底质量有限）。
4. answer_relevancy 是**内部指标**（RAGAS 用反向生成问题近似，噪声大），不进入简历表述。

## 2. 执行与稳定性披露

本闭环经历的环境/稳定性问题，均已在最终数据中如实处理：

| 问题 | 处理 |
|---|---|
| Docker 虚拟化失效（`virtualisation support wasn't detected`） | 打分阶段不依赖后端（仅 SiliconFlow 评估 LLM），用独立打分脚本 `_score_checkpoint.py` 先对 57 条已采集样本打分；Docker 恢复后补采集 3 条 relation |
| 第一轮并发打分大量超时（faithfulness 42% None、context_precision 75% None） | 定位为并发放大 SiliconFlow 网络抖动（非真实低分，单条重跑全成功）；用 `_retry_none.py` 小批量（3 条/批）+ 600s 超时 + 断点续跑补齐，最终核心指标 None 降至 faithfulness 2 / context_precision 2 / context_recall 5 |
| relation 层 contexts 偶发为空（超图通道稳定性） | `_retry_collect.py` 重试后补齐（001/014 各拿到 10 个 contexts） |
| answer_relevancy 剩余 25 个 None | 内部指标、不进入简历，且首轮并发即成功部分已足够；未再补跑 |

## 3. 复现命令

```bash
cd ragent/scripts/eval
# 采集(需 backend) + 打分
$env:SILICONFLOW_API_KEY = "<key>"
.\.venv\Scripts\python.exe ragas_eval.py --dataset datasets/industrial_main_r5.jsonl --per-group 15 --out report/r5d_20260815/ragas_report.json
# 补采集缺失 relation(断点)
.\.venv\Scripts\python.exe _collect_missing.py
# 补跑并发超时的 None 指标(断点续跑)
.\.venv\Scripts\python.exe _retry_none.py --score-report report/r5d_20260815/ragas_score_only.json --checkpoint report/r5d_20260815/ragas_report.checkpoint.jsonl --out report/r5d_20260815/ragas_report.json
# 合并最终 60 条
.\.venv\Scripts\python.exe _merge_final.py
```

## 4. 产出文件

- `ragas_report_final.json`：最终 60 条报告（含 per_sample 逐条 4 指标 + 分层汇总 + 执行指纹）
- `ragas_report.checkpoint.jsonl`：采集断点（60 条 collected，含 answer/contexts/reference）
- `ragas_score_only.json`：57 条首轮打分（Docker 不可用期间）
- `ragas_new_relation.json`：3 条新 relation 打分
- 中间脚本：`_score_checkpoint.py` / `_retry_none.py` / `_collect_missing.py` / `_retry_collect.py` / `_score_new_relation.py` / `_merge_final.py`

## 5. R5-D 简历门槛对照

R5-D 门槛「60 条 RAGAS 评测（四层各 15 条）」已达成。可写限制性表述：

> 在 60 条固定评测集（事实/噪声/图像/关系四层各 15）上，RAGAS faithfulness 0.61、context_recall 0.67、context_precision 0.63；其中文本事实问答最优（faithfulness 0.74），图像场景上下文精准度最弱（0.27，检索瓶颈），关系场景生成忠实度最弱（0.47，生成瓶颈）。
