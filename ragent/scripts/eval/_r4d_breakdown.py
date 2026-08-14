#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R4-D 后处理:按素材类别(subcategory)/split 分组统计 Hit/MRR/P95/置信区间。

输入:retrieval_eval.py 生成的报告(含 results,每条有 business_tags/split/hit/mrr)。
输出:分组汇总 JSON,含 Wilson 置信区间。
"""
import json
import math
from pathlib import Path

TOPK = [1, 3, 5]


def wilson_interval(success: int, total: int, z: float = 1.96) -> tuple[float, float]:
    """Wilson score interval for a proportion."""
    if total == 0:
        return 0.0, 0.0
    p = success / total
    denom = 1 + z * z / total
    center = p + z * z / (2 * total)
    margin = z * math.sqrt(p * (1 - p) / total + z * z / (4 * total * total))
    return max(0.0, (center - margin) / denom), min(1.0, (center + margin) / denom)


def latency_percentile(sorted_ms: list[int], p: float) -> int:
    if not sorted_ms:
        return 0
    idx = max(0, min(len(sorted_ms) - 1, int(math.ceil(p / 100 * len(sorted_ms)) - 1)))
    return sorted_ms[idx]


def group_metrics(cases: list[dict]) -> dict:
    quality = [c for c in cases if c["ok"]]
    total = len(cases)
    q = len(quality)
    return {
        "count": total,
        "quality_sample_count": q,
        "excluded_execution_count": total - q,
        "hit_rate": {
            f"@{k}": round(sum(1 for c in quality if c["hit"][str(k)]) / max(1, q), 4)
            for k in TOPK
        },
        "hit_rate_wilson95": {
            f"@{k}": [round(x, 4) for x in wilson_interval(
                sum(1 for c in quality if c["hit"][str(k)]), q)]
            for k in TOPK
        },
        "mrr": round(sum(c["mrr"] for c in quality) / max(1, q), 4),
        # 口径与 retrieval_eval 顶层 summary 一致:channel_hit/source_id_hit
        # 只对质量样本(ok)计分母,执行失败样本不混入质量统计。
        "channel_hit_rate": round(sum(1 for c in quality if c.get("channel_hit")) / max(1, q), 4),
        "source_id_hit_rate": round(sum(1 for c in quality if c.get("source_id_hit")) / max(1, q), 4),
        "latency": {
            "p50_ms": latency_percentile(sorted(c["latency_ms"] for c in quality), 50),
            "p95_ms": latency_percentile(sorted(c["latency_ms"] for c in quality), 95),
            "max_ms": max((c["latency_ms"] for c in quality), default=0),
        },
    }


def main() -> None:
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, required=True,
                        help="retrieval_eval 报告 JSON")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    report = json.loads(args.report.read_text(encoding="utf-8"))
    results = report["results"]

    def key_subcategory(c):
        tags = c.get("business_tags") or []
        return tags[0] if tags else "unknown"

    def key_split(c):
        return c.get("split") or "unknown"

    breakdown = {
        "source": report.get("mode"),
        "overall": group_metrics(results),
        "by_subcategory": {
            sc: group_metrics([c for c in results if key_subcategory(c) == sc])
            for sc in sorted({key_subcategory(c) for c in results})
        },
        "by_split": {
            sp: group_metrics([c for c in results if key_split(c) == sp])
            for sp in sorted({key_split(c) for c in results})
        },
        "by_subcategory_split": {
            f"{sc}/{sp}": group_metrics(
                [c for c in results if key_subcategory(c) == sc and key_split(c) == sp])
            for sc in sorted({key_subcategory(c) for c in results})
            for sp in sorted({key_split(c) for c in results})
        },
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(breakdown, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(breakdown, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
