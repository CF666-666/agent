#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Merge scene-batched retrieval reports into one reproducible report."""

import argparse
import json
from collections import defaultdict
from pathlib import Path


TOPK = (1, 3, 5)
REPORT_SCHEMA_VERSION = 2


def summarize(results: list[dict]) -> dict:
    total = len(results)
    by_scene = defaultdict(list)
    for result in results:
        by_scene[result.get("scene", "")].append(result)

    def ratio(items, predicate):
        return round(sum(1 for item in items if predicate(item)) / len(items), 4) if items else 0.0

    return {
        "total": total,
        "no_retrieval": sum(1 for item in results if not item.get("ok")),
        "hit_rate": {
            f"@{k}": ratio(results, lambda item, k=k: item.get("hit", {}).get(str(k), False))
            for k in TOPK
        },
        "mrr": round(sum(float(item.get("mrr", 0.0)) for item in results) / total, 4) if total else 0.0,
        "expected_channel_hit_rate": ratio(results, lambda item: item.get("channel_hit", False)),
        "source_id_hit_rate": ratio(results, lambda item: item.get("source_id_hit", False)),
        "by_scene": {
            scene: {
                "count": len(items),
                "hit_rate": {
                    f"@{k}": ratio(items, lambda item, k=k: item.get("hit", {}).get(str(k), False))
                    for k in TOPK
                },
                "mrr": round(sum(float(item.get("mrr", 0.0)) for item in items) / len(items), 4),
                "expected_channel_hit_rate": ratio(items, lambda item: item.get("channel_hit", False)),
                "source_id_hit_rate": ratio(items, lambda item: item.get("source_id_hit", False)),
            }
            for scene, items in sorted(by_scene.items())
        },
    }


def merge_documents(documents: list[dict], report_paths: list[Path]) -> dict:
    if not documents:
        raise ValueError("at least one report is required")

    schemas = {document.get("schema_version") for document in documents}
    if schemas != {REPORT_SCHEMA_VERSION}:
        raise ValueError(f"reports must use schema {REPORT_SCHEMA_VERSION}: {sorted(schemas, key=str)}")

    modes = {document.get("mode") for document in documents}
    if len(modes) != 1:
        raise ValueError(f"reports use different modes: {sorted(modes)}")

    datasets = {json.dumps(document.get("dataset"), sort_keys=True) for document in documents}
    if len(datasets) != 1:
        raise ValueError("reports use different datasets")

    options = {json.dumps(document.get("retrieval_options"), sort_keys=True) for document in documents}
    if len(options) != 1:
        raise ValueError("reports use different retrieval options")

    runtimes = {json.dumps(document.get("runtime"), sort_keys=True) for document in documents}
    if len(runtimes) != 1:
        raise ValueError("reports use different runtimes")

    results = [result for document in documents for result in document.get("results", [])]
    case_ids = [result.get("case_id") for result in results]
    if any(case_ids):
        if not all(case_ids):
            raise ValueError("reports mix results with and without case_id")
        if len(case_ids) != len(set(case_ids)):
            raise ValueError("reports contain duplicate evaluation cases")
    return {
        "schema_version": REPORT_SCHEMA_VERSION,
        "mode": documents[0]["mode"],
        "dataset": documents[0]["dataset"],
        "retrieval_options": documents[0]["retrieval_options"],
        "runtime": documents[0].get("runtime"),
        "summary": summarize(results),
        "results": results,
        "batch_reports": [str(path) for path in report_paths],
        "batch_slices": [document.get("evaluation_slice") for document in documents],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="merge scene-batched retrieval reports")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("reports", type=Path, nargs="+")
    args = parser.parse_args()

    documents = [json.loads(path.read_text(encoding="utf-8")) for path in args.reports]
    merged = merge_documents(documents, args.reports)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(merged, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(merged["summary"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
