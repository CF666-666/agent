#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""合并最终 RAGAS 报告:57 条(补跑后) + 3 条新 relation = 60 条。

处理 nan -> None,重新汇总 summary/groups/sample_status,对齐 ragas_eval.py schema。
"""
import json
import math
import sys
from collections import Counter, OrderedDict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ragas_eval_core import (  # noqa: E402
    INTERNAL_METRICS, RAGAS_GROUP_ORDER, REPORT_SCHEMA_VERSION, metric_summary,
)

METRIC_NAMES = ("faithfulness", "answer_relevancy", "context_precision", "context_recall")


def clean(v):
    """NaN/None -> None,否则保留 4 位 float。"""
    if v is None:
        return None
    if isinstance(v, float) and math.isnan(v):
        return None
    return round(float(v), 4)


def main() -> int:
    base = json.loads(Path("report/r5d_20260815/ragas_report.json").read_text(encoding="utf-8"))
    new_rel = json.loads(Path("report/r5d_20260815/ragas_new_relation.json").read_text(encoding="utf-8"))

    # 清洗 base 的 per_sample
    base_per = []
    for p in base["per_sample"]:
        entry = {k: p[k] for k in ("case_id", "query", "ragas_group", "business_tags")}
        for name in METRIC_NAMES:
            entry[name] = clean(p.get(name))
        base_per.append(entry)

    # 合并 3 条新 relation(若 base 里已存在则覆盖)
    base_by_id = {p["case_id"]: p for p in base_per}
    for p in new_rel:
        entry = {k: p[k] for k in ("case_id", "query", "ragas_group", "business_tags")}
        for name in METRIC_NAMES:
            entry[name] = clean(p.get(name))
        base_by_id[entry["case_id"]] = entry

    per_sample = [base_by_id[cid] for cid in sorted(base_by_id)]
    print(f"[merge] 合并后 {len(per_sample)} 条")

    # 重新汇总
    group_values = OrderedDict((g, {n: [] for n in METRIC_NAMES}) for g in RAGAS_GROUP_ORDER)
    all_values = {n: [] for n in METRIC_NAMES}
    for p in per_sample:
        group = p["ragas_group"]
        for name in METRIC_NAMES:
            v = p[name]
            if v is not None:
                group_values[group][name].append(v)
                all_values[name].append(v)

    summary = {"samples": len(per_sample),
               **{n: metric_summary(all_values[n]) for n in METRIC_NAMES}}
    group_counts = Counter(p["ragas_group"] for p in per_sample)
    groups = OrderedDict()
    for g in RAGAS_GROUP_ORDER:
        if g not in group_counts:
            continue
        groups[g] = {"n": group_counts[g],
                     **{n: metric_summary(group_values[g][n]) for n in METRIC_NAMES}}

    # 统计剩余 None
    remaining_none = {n: sum(1 for p in per_sample if p[n] is None) for n in METRIC_NAMES}
    failed_score = sum(1 for p in per_sample
                       if all(p[n] is None for n in
                              ("faithfulness", "context_precision", "context_recall")))

    business_tags = Counter()
    for p in per_sample:
        for tag in p["business_tags"]:
            business_tags[tag] += 1

    out = {
        "schema_version": REPORT_SCHEMA_VERSION,
        "mode": "merged",
        "note": "Docker 恢复后补采 3 条 relation + 补跑并发超时的 None 指标;"
                "relation 层 contexts 偶发为空(超图通道稳定性),重试后补齐。",
        "summary": summary,
        "groups": groups,
        "sample_status": {
            "scored": len(per_sample) - failed_score,
            "failed_collect": 0,
            "failed_score": failed_score,
        },
        "remaining_none_after_retry": remaining_none,
        "business_tags": dict(sorted(business_tags.items())),
        "internal_metrics": list(INTERNAL_METRICS),
        "confidence_interval": base.get("confidence_interval", {}),
        "execution_fingerprint": base.get("execution_fingerprint", {}),
        "per_sample": per_sample,
    }
    out_path = Path("report/r5d_20260815/ragas_report_final.json")
    out_path.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8")
    print(f"[merge] 写入 {out_path}")
    print(json.dumps({"summary": summary, "remaining_none": remaining_none,
                      "sample_status": out["sample_status"]},
                     ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
