#!/usr/bin/env python3
"""Classify R2 relation failures from structured evaluation evidence."""

import argparse
import json
from collections import Counter
from pathlib import Path


def bool_at(metric: dict, key: str) -> bool:
    rank = key.lstrip("@")
    return bool(metric.get(key, metric.get(rank, metric.get(int(rank), False))))


def number_at(metric: dict, key: str) -> float:
    rank = key.lstrip("@")
    return float(metric.get(key, metric.get(rank, metric.get(int(rank), 0.0))))


def classify_result(result: dict, fusion_enabled: bool = False) -> str:
    tags = result.get("business_tags") or []
    if not result.get("ok"):
        return "execution_failed_unattributed"

    relation = result.get("relation") or {}
    channel = relation.get("channel") or {}
    final = relation.get("final_reference") or {}
    channel_hit = channel.get("hyperedge_hit") or {}
    channel_recall = channel.get("hyperedge_recall") or {}
    final_hit = final.get("hit") or {}
    if not bool_at(channel_hit, "@5") or number_at(channel_recall, "@5") < 1.0:
        return "hyperedge_not_recalled"
    if not channel.get("source_accuracy"):
        return "source_id_mismatch"
    if not channel.get("path_hit"):
        return "ranking_error"
    if fusion_enabled and bool_at(channel_hit, "@1") and not bool_at(final_hit, "@1"):
        return "fusion_crowd_out"
    if not bool_at(channel_hit, "@1"):
        return "ranking_error"
    return "passed"


def classify_report(report: dict) -> dict:
    fusion_enabled = bool((report.get("retrieval_options") or {}).get("enableFusion"))
    classified = []
    for result in report.get("results", []):
        category = classify_result(result, fusion_enabled=fusion_enabled)
        classified.append({
            "dataset_id": result.get("dataset_id"),
            "split": result.get("split"),
            "query": result.get("query"),
            "business_tags": result.get("business_tags", []),
            "category": category,
            "retrieval_status": result.get("retrieval_status"),
            "golden_hyperedge_ids": result.get("golden_hyperedge_ids", []),
            "ranked_hyperedge_ids": ((result.get("relation") or {}).get("channel") or {})
                .get("ranked_hyperedge_ids", []),
        })
    counts = Counter(item["category"] for item in classified)
    by_split = {
        split: dict(sorted(Counter(item["category"] for item in classified
                                   if item["split"] == split).items()))
        for split in ("tuning", "frozen")
    }
    return {
        "source_report": report.get("runtime", {}).get("label"),
        "dataset": report.get("dataset"),
        "counts": dict(sorted(counts.items())),
        "by_split": by_split,
        "results": classified,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    report = json.loads(args.report.read_text(encoding="utf-8"))
    classified = classify_report(report)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(classified, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"counts": classified["counts"], "by_split": classified["by_split"]},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
