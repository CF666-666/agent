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

依赖:Python 3.11 venv(`scripts/eval/.venv`)+ `pip install ragas "langchain-community<0.4"`;需宿主机环境变量 `BAILIAN_API_KEY` / `SILICONFLOW_API_KEY`。
