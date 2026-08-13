#!/usr/bin/env python3
"""Validate and compare a strict rewrite-off/on conversation A/B pair."""

import argparse
import json
import re
from pathlib import Path


def _same(left, right, label: str) -> None:
    if left != right:
        raise ValueError(f"A/B {label} mismatch")


def _options_without_rewrite(report: dict) -> dict:
    options = dict(report.get("retrieval_options") or {})
    options.pop("enableRewrite", None)
    return options


def validate_pair(off: dict, on: dict) -> None:
    if off.get("mode") != "rewrite-off" or on.get("mode") != "rewrite-on":
        raise ValueError("reports must be ordered rewrite-off then rewrite-on")
    if (off.get("retrieval_options") or {}).get("enableRewrite") is not False:
        raise ValueError("rewrite-off report has invalid enableRewrite")
    if (on.get("retrieval_options") or {}).get("enableRewrite") is not True:
        raise ValueError("rewrite-on report has invalid enableRewrite")
    _same(off.get("dataset"), on.get("dataset"), "dataset")
    _same(_options_without_rewrite(off), _options_without_rewrite(on), "retrieval options")
    _same(off.get("runtime"), on.get("runtime"), "runtime")
    _same(off.get("execution_fingerprint"), on.get("execution_fingerprint"),
          "execution fingerprint")
    _same(off.get("evaluation_slice"), on.get("evaluation_slice"), "evaluation slice")
    off_warmup, on_warmup = off.get("warmup") or {}, on.get("warmup") or {}
    _same(
        {key: off_warmup.get(key) for key in ("requested_count", "executed_count", "query")},
        {key: on_warmup.get(key) for key in ("requested_count", "executed_count", "query")},
        "warmup conditions")
    if not off_warmup.get("results") or not on_warmup.get("results"):
        raise ValueError("A/B warmup results are missing")
    if not all(item.get("ok") for item in off_warmup["results"] + on_warmup["results"]):
        raise ValueError("A/B warmup must succeed in both runs")
    backend_image = str((off.get("runtime") or {}).get("backend_image") or "")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", backend_image):
        raise ValueError("A/B requires immutable backend image sha256")
    off_cases = [(item.get("dataset_id"), item.get("target_query"))
                 for item in off.get("results") or []]
    on_cases = [(item.get("dataset_id"), item.get("target_query"))
                for item in on.get("results") or []]
    _same(off_cases, on_cases, "case order")
    if not off_cases:
        raise ValueError("A/B reports contain no cases")


def compare(off: dict, on: dict) -> dict:
    validate_pair(off, on)
    off_summary, on_summary = off["summary"], on["summary"]
    paired_cases = []
    for left, right in zip(off["results"], on["results"]):
        paired_cases.append({
            "dataset_id": left["dataset_id"],
            "target_query": left["target_query"],
            "rewrite_off": {"ok": left["ok"], "mrr": left.get("mrr", 0.0)},
            "rewrite_on": {"ok": right["ok"], "mrr": right.get("mrr", 0.0)},
            "mrr_delta": round(float(right.get("mrr", 0.0)) - float(left.get("mrr", 0.0)), 4),
        })
    common = [case for case in paired_cases
              if case["rewrite_off"]["ok"] and case["rewrite_on"]["ok"]]
    common_count = len(common)
    off_mrr = sum(case["rewrite_off"]["mrr"] for case in common) / common_count if common_count else None
    on_mrr = sum(case["rewrite_on"]["mrr"] for case in common) / common_count if common_count else None
    off_by_id = {item["dataset_id"]: item for item in off["results"]}
    on_by_id = {item["dataset_id"]: item for item in on["results"]}
    off_hit1 = sum(bool(off_by_id[case["dataset_id"]].get("hit", {}).get("1")
                        or off_by_id[case["dataset_id"]].get("hit", {}).get(1)) for case in common) / common_count if common_count else None
    on_hit1 = sum(bool(on_by_id[case["dataset_id"]].get("hit", {}).get("1")
                       or on_by_id[case["dataset_id"]].get("hit", {}).get(1)) for case in common) / common_count if common_count else None
    return {
        "comparison_type": "strict_paired_rewrite_ab",
        "dataset": off["dataset"],
        "runtime": off["runtime"],
        "execution_fingerprint": off["execution_fingerprint"],
        "rewrite_off_summary": off_summary,
        "rewrite_on_summary": on_summary,
        "paired_quality_sample_count": common_count,
        "execution_success_count": {
            "rewrite_off": sum(bool(item.get("ok")) for item in off["results"]),
            "rewrite_on": sum(bool(item.get("ok")) for item in on["results"]),
        },
        "execution_success_rate": {
            "total": len(off["results"]),
            "rewrite_off": round(sum(bool(item.get("ok")) for item in off["results"]) / len(off["results"]), 4),
            "rewrite_on": round(sum(bool(item.get("ok")) for item in on["results"]) / len(on["results"]), 4),
        },
        "delta": {
            "hit_at_1_absolute": round(on_hit1 - off_hit1, 4) if common_count else None,
            "mrr_absolute": round(on_mrr - off_mrr, 4) if common_count else None,
            "mrr_relative": round((on_mrr - off_mrr) / off_mrr, 4) if off_mrr else None,
        },
        "paired_cases": paired_cases,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rewrite-off", type=Path, required=True)
    parser.add_argument("--rewrite-on", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    result = compare(
        json.loads(args.rewrite_off.read_text(encoding="utf-8")),
        json.loads(args.rewrite_on.read_text(encoding="utf-8")),
    )
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result["delta"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
