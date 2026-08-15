#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""独立打分脚本:直接读 checkpoint 里已采集的 collected 样本做 RAGAS 打分,
跳过采集阶段(不依赖后端 Docker)。用于 Docker 不可用时推进 R5-D 打分。

输出 schema 与 ragas_eval.py 一致,便于后续补 3 条 relation 后合并。
"""
import argparse
import json
import math
import os
import re
import sys
from collections import Counter, OrderedDict
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
from ragas_eval_core import (  # noqa: E402
    RAGAS_GROUP_ORDER, INTERNAL_METRICS, REPORT_SCHEMA_VERSION, metric_summary,
)
from runtime_fingerprint import (  # noqa: E402
    DEFAULT_APPLICATION_CONFIG, DEFAULT_PROFILE, build_execution_fingerprint,
)

SILICON_BASE = "https://api.siliconflow.cn/v1"
ALL_METRICS = (faithfulness, answer_relevancy, context_precision, context_recall)


def load_checkpoint_unique(path: Path) -> list[dict]:
    """读 checkpoint,按 case_id 去重保留最后一条,collected 优先。"""
    seen = {}
    order = []
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                continue
            cid = record.get("case_id")
            if not cid:
                continue
            if cid not in seen:
                order.append(cid)
            seen[cid] = record
    return [seen[cid] for cid in order if seen[cid].get("status") == "collected"]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--eval-model", default="deepseek-ai/DeepSeek-V3.2")
    parser.add_argument("--embedding-model", default="Qwen/Qwen3-Embedding-8B")
    args = parser.parse_args()

    api_key = os.environ.get("SILICONFLOW_API_KEY", "").strip()
    if not api_key:
        print("[error] SILICONFLOW_API_KEY not configured", file=sys.stderr)
        sys.exit(1)

    collected = load_checkpoint_unique(args.checkpoint)
    if not collected:
        print("[error] no collected records in checkpoint", file=sys.stderr)
        sys.exit(1)
    print(f"[score_only] 从 checkpoint 加载 {len(collected)} 条 collected 样本")

    eval_llm = LangchainLLMWrapper(ChatOpenAI(
        model=args.eval_model, api_key=api_key, base_url=SILICON_BASE,
        temperature=0, request_timeout=180))
    eval_emb = LangchainEmbeddingsWrapper(OpenAIEmbeddings(
        model=args.embedding_model, api_key=api_key, base_url=SILICON_BASE))

    samples = [SingleTurnSample(
        user_input=r["query"], response=r["answer"],
        retrieved_contexts=r["contexts"], reference=r["reference"],
    ) for r in collected]

    result = None
    last_error = None
    for attempt in range(2):
        try:
            result = evaluate(
                EvaluationDataset(samples=samples),
                metrics=list(ALL_METRICS), llm=eval_llm, embeddings=eval_emb)
            break
        except Exception as e:  # noqa: BLE001
            last_error = e
            print(f"    [warn] 打分第 {attempt + 1} 次失败: {e}", file=sys.stderr)
    if result is None:
        print(f"[error] 打分失败: {last_error}", file=sys.stderr)
        sys.exit(1)

    df = result.to_pandas()
    df_records = df.to_dict(orient="records")
    if len(df_records) != len(collected):
        raise RuntimeError(
            f"ragas 返回 {len(df_records)} 行与样本 {len(collected)} 不一致")

    metric_names = [m.name for m in ALL_METRICS]
    per_sample = []
    failed_score = 0
    group_values = OrderedDict((g, {n: [] for n in metric_names}) for g in RAGAS_GROUP_ORDER)
    all_values = {n: [] for n in metric_names}
    business_tags_counter = Counter()

    for record, row in zip(collected, df_records):
        group = record["ragas_group"]
        row_values = {}
        for name in metric_names:
            value = row.get(name)
            if value is None or (isinstance(value, float) and math.isnan(value)):
                value = None
            else:
                value = float(value)
            row_values[name] = value
            if value is not None:
                group_values[group][name].append(value)
                all_values[name].append(value)
        core = [row_values.get("faithfulness"), row_values.get("context_precision"),
                row_values.get("context_recall")]
        if all(v is None for v in core):
            failed_score += 1
        for tag in record.get("business_tags") or []:
            business_tags_counter[tag] += 1
        per_sample.append({
            "case_id": record["case_id"], "query": record["query"],
            "ragas_group": group, "business_tags": record.get("business_tags", []),
            **{n: (round(row_values[n], 4) if row_values[n] is not None else None)
               for n in metric_names},
        })

    summary = {"samples": len(collected),
               **{n: metric_summary(all_values[n]) for n in metric_names}}
    group_counts = Counter(r["ragas_group"] for r in collected)
    groups = OrderedDict()
    for group in RAGAS_GROUP_ORDER:
        if group not in group_counts:
            continue
        values = group_values[group]
        groups[group] = {"n": group_counts[group],
                         **{n: metric_summary(values[n]) for n in metric_names}}

    # 从 checkpoint 统计真实 failed_collect(本 checkpoint 无 failed_collect,
    # relation 缺 3 条是「未采集」而非「采集失败」,由调用方在合并时补采)
    all_records = load_checkpoint_unique(args.checkpoint)
    failed_collect = sum(1 for r in all_records if r.get("status") != "collected")
    sample_status = {"scored": len(collected) - failed_score,
                     "failed_collect": failed_collect, "failed_score": failed_score}

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps({
        "schema_version": REPORT_SCHEMA_VERSION,
        "mode": "score_only",
        "note": ("Docker 不可用期间对 checkpoint 已采集样本独立打分;"
                 "relation 层缺 3 条未采集样本,待后端恢复后补采并合并。"),
        "summary": summary,
        "groups": groups,
        "sample_status": sample_status,
        "business_tags": dict(sorted(business_tags_counter.items())),
        "internal_metrics": list(INTERNAL_METRICS),
        "confidence_interval": {"method": "t_distribution_cornish_fisher", "level": 0.95,
                                "note": "n<2 时 ci95_half 为 null;内部指标 answer_relevancy 不进入简历"},
        "execution_fingerprint": build_execution_fingerprint(
            Path(__file__), DEFAULT_PROFILE, DEFAULT_APPLICATION_CONFIG,
            extra={"ragas_evaluator": {"provider": "siliconflow",
                                       "model": args.eval_model,
                                       "embedding_model": args.embedding_model}}),
        "per_sample": per_sample,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
