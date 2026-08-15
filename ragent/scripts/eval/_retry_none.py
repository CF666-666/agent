#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""补跑 RAGAS 打分中因并发超时导致 None 的 faithfulness/context_precision。

策略:小批量(每批 3 条)+ 长超时(600s)+ 断点续跑,避免并发放大网络抖动。
合并后输出最终报告(对齐 ragas_eval.py schema)。
"""
import argparse
import json
import math
import os
import sys
from collections import Counter, OrderedDict
from pathlib import Path

from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from ragas import EvaluationDataset, SingleTurnSample, evaluate
from ragas.embeddings import LangchainEmbeddingsWrapper
from ragas.llms import LangchainLLMWrapper
from ragas.metrics import context_precision, faithfulness

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))
from ragas_eval_core import (  # noqa: E402
    INTERNAL_METRICS, RAGAS_GROUP_ORDER, REPORT_SCHEMA_VERSION, metric_summary,
)

SILICON_BASE = "https://api.siliconflow.cn/v1"
BATCH = 3


def load_checkpoint_unique(path: Path) -> dict:
    seen = {}
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
            if cid:
                seen[cid] = record
    return seen


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--score-report", type=Path, required=True)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--eval-model", default="deepseek-ai/DeepSeek-V3.2")
    args = parser.parse_args()

    api_key = os.environ.get("SILICONFLOW_API_KEY", "").strip()
    if not api_key:
        print("[error] SILICONFLOW_API_KEY not configured", file=sys.stderr)
        sys.exit(1)

    report = json.loads(args.score_report.read_text(encoding="utf-8"))
    checkpoint = load_checkpoint_unique(args.checkpoint)
    per_sample = report["per_sample"]

    # 找需要补跑的样本(faithfulness 或 context_precision 为 None)
    retry_ids = [p["case_id"] for p in per_sample
                 if p["faithfulness"] is None or p["context_precision"] is None]
    print(f"[retry] 需补跑 {len(retry_ids)} 条(None 指标)")

    llm = LangchainLLMWrapper(ChatOpenAI(
        model=args.eval_model, api_key=api_key, base_url=SILICON_BASE,
        temperature=0, request_timeout=600))
    emb = LangchainEmbeddingsWrapper(OpenAIEmbeddings(
        model="Qwen/Qwen3-Embedding-8B", api_key=api_key, base_url=SILICON_BASE))

    # 断点文件
    patch_path = args.out.with_name(args.out.stem + ".retry.json")
    patch = {}
    if patch_path.exists():
        patch = json.loads(patch_path.read_text(encoding="utf-8"))

    for i in range(0, len(retry_ids), BATCH):
        batch_ids = retry_ids[i:i + BATCH]
        # 跳过已完成的
        todo = [cid for cid in batch_ids if cid not in patch]
        if not todo:
            continue
        samples = [SingleTurnSample(
            user_input=checkpoint[cid]["query"],
            response=checkpoint[cid]["answer"],
            retrieved_contexts=checkpoint[cid]["contexts"],
            reference=checkpoint[cid]["reference"],
        ) for cid in todo]
        ok = False
        for attempt in range(3):
            try:
                result = evaluate(
                    EvaluationDataset(samples=samples),
                    metrics=[faithfulness, context_precision], llm=llm, embeddings=emb)
                df = result.to_pandas()
                recs = df.to_dict(orient="records")
                for cid, rec in zip(todo, recs):
                    patch[cid] = {
                        "faithfulness": (round(float(rec["faithfulness"]), 4)
                                         if rec.get("faithfulness") is not None else None),
                        "context_precision": (round(float(rec["context_precision"]), 4)
                                              if rec.get("context_precision") is not None else None),
                    }
                ok = True
                break
            except Exception as e:  # noqa: BLE001
                print(f"    [warn] 批 {i // BATCH} 第 {attempt + 1} 次失败: {e}", file=sys.stderr)
        if ok:
            patch_path.write_text(json.dumps(patch, ensure_ascii=False, indent=2) + "\n",
                                  encoding="utf-8")
            print(f"    [ok] 批 {i // BATCH} 完成,已补 {len(patch)}/{len(retry_ids)}")
        else:
            print(f"    [fail] 批 {i // BATCH} 三次重试均失败", file=sys.stderr)

    # 合并:用 patch 覆盖 None 值
    for p in per_sample:
        cid = p["case_id"]
        if cid in patch:
            for name in ("faithfulness", "context_precision"):
                v = patch[cid].get(name)
                if v is not None and p[name] is None:
                    p[name] = v

    # 重新汇总
    metric_names = ("faithfulness", "context_precision", "context_recall", "answer_relevancy")
    group_values = OrderedDict((g, {n: [] for n in metric_names}) for g in RAGAS_GROUP_ORDER)
    all_values = {n: [] for n in metric_names}
    for p in per_sample:
        group = p["ragas_group"]
        for name in metric_names:
            v = p[name]
            if v is not None:
                group_values[group][name].append(v)
                all_values[name].append(v)

    summary = {"samples": len(per_sample),
               **{n: metric_summary(all_values[n]) for n in metric_names}}
    group_counts = Counter(p["ragas_group"] for p in per_sample)
    groups = OrderedDict()
    for g in RAGAS_GROUP_ORDER:
        if g not in group_counts:
            continue
        groups[g] = {"n": group_counts[g],
                     **{n: metric_summary(group_values[g][n]) for n in metric_names}}

    # 统计剩余 None
    remaining_none = {n: sum(1 for p in per_sample if p[n] is None) for n in metric_names}
    failed_score = sum(1 for p in per_sample if all(p[n] is None for n in
                      ("faithfulness", "context_precision", "context_recall")))

    out_report = dict(report)
    out_report.update({
        "mode": "score_only_retried",
        "summary": summary,
        "groups": groups,
        "sample_status": {
            "scored": len(per_sample) - failed_score,
            "failed_collect": report["sample_status"].get("failed_collect", 0),
            "failed_score": failed_score,
        },
        "remaining_none_after_retry": remaining_none,
        "per_sample": per_sample,
    })
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(out_report, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8")
    print(json.dumps({"remaining_none": remaining_none, "summary": summary},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
