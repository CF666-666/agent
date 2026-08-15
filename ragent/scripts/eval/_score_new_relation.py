#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对 3 条新补采的 relation 样本打分(小批量 + 长超时),输出 per_sample 结构。"""
import json
import os
import re
import sys
from pathlib import Path

from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from ragas import EvaluationDataset, SingleTurnSample, evaluate
from ragas.embeddings import LangchainEmbeddingsWrapper
from ragas.llms import LangchainLLMWrapper
from ragas.metrics import (
    answer_relevancy, context_precision, context_recall, faithfulness,
)

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))
from ragas_eval_core import load_checkpoint  # noqa: E402

SILICON_BASE = "https://api.siliconflow.cn/v1"
TARGETS = ("r2-relation-frozen-001", "r2-relation-frozen-008", "r2-relation-frozen-014")
METRICS = (faithfulness, answer_relevancy, context_precision, context_recall)


def main() -> int:
    api_key = os.environ.get("SILICONFLOW_API_KEY", "").strip()
    if not api_key:
        print("[error] SILICONFLOW_API_KEY not configured", file=sys.stderr)
        return 1

    checkpoint = load_checkpoint(Path("report/r5d_20260815/ragas_report.checkpoint.jsonl"))
    records = [checkpoint[cid] for cid in TARGETS if cid in checkpoint]
    print(f"[score_new] 对 {len(records)} 条新 relation 打分")

    llm = LangchainLLMWrapper(ChatOpenAI(
        model="deepseek-ai/DeepSeek-V3.2", api_key=api_key, base_url=SILICON_BASE,
        temperature=0, request_timeout=600))
    emb = LangchainEmbeddingsWrapper(OpenAIEmbeddings(
        model="Qwen/Qwen3-Embedding-8B", api_key=api_key, base_url=SILICON_BASE))

    samples = [SingleTurnSample(
        user_input=r["query"], response=r["answer"],
        retrieved_contexts=r["contexts"], reference=r["reference"],
    ) for r in records]

    result = None
    for attempt in range(3):
        try:
            result = evaluate(
                EvaluationDataset(samples=samples),
                metrics=list(METRICS), llm=llm, embeddings=emb)
            break
        except Exception as e:  # noqa: BLE001
            print(f"    [warn] 第 {attempt + 1} 次失败: {e}", file=sys.stderr)
    if result is None:
        print("[error] 3 次打分均失败", file=sys.stderr)
        return 1

    df = result.to_pandas()
    recs = df.to_dict(orient="records")
    metric_names = [m.name for m in METRICS]
    per_sample = []
    for record, row in zip(records, recs):
        entry = {
            "case_id": record["case_id"], "query": record["query"],
            "ragas_group": record["ragas_group"],
            "business_tags": record.get("business_tags", []),
        }
        for name in metric_names:
            v = row.get(name)
            if v is None or (isinstance(v, float) and v != v):  # None 或 NaN
                entry[name] = None
            else:
                entry[name] = round(float(v), 4)
        per_sample.append(entry)

    out = Path("report/r5d_20260815/ragas_new_relation.json")
    out.write_text(json.dumps(per_sample, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print(json.dumps(per_sample, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
