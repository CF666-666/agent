# -*- coding: utf-8 -*-
"""R5-B 主集合并报告:合并三个场景报告,计算宏平均(各场景简单平均)与
微平均(全部样本合并),输出场景分布/超时分布/空引用分布。"""
import json
import math
from pathlib import Path

TOPK = [1, 3, 5]


def wilson(success, total, z=1.96):
    if total == 0:
        return 0.0, 0.0
    p = success / total
    denom = 1 + z * z / total
    center = p + z * z / (2 * total)
    margin = z * math.sqrt(p * (1 - p) / total + z * z / (4 * total * total))
    return max(0.0, (center - margin) / denom), min(1.0, (center + margin) / denom)


def pct(sorted_ms, p):
    if not sorted_ms:
        return 0
    idx = max(0, min(len(sorted_ms) - 1, int(math.ceil(p / 100 * len(sorted_ms))) - 1))
    return sorted_ms[idx]


def scene_metrics(cases):
    quality = [c for c in cases if c["ok"]]
    q = len(quality)
    return {
        "count": len(cases),
        "quality_sample_count": q,
        "excluded_execution_count": len(cases) - q,
        "hit_rate": {f"@{k}": round(sum(1 for c in quality if c["hit"][str(k)]) / max(1, q), 4) for k in TOPK},
        "mrr": round(sum(c["mrr"] for c in quality) / max(1, q), 4),
        "latency_p50_ms": pct(sorted(c["latency_ms"] for c in quality), 50),
        "latency_p95_ms": pct(sorted(c["latency_ms"] for c in quality), 95),
        "no_retrieval_count": len(cases) - q,
    }


def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--text", type=Path, required=True)
    parser.add_argument("--image", type=Path, required=True)
    parser.add_argument("--relation", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    reports = {
        "text": json.loads(args.text.read_text(encoding="utf-8")),
        "image": json.loads(args.image.read_text(encoding="utf-8")),
        "relation": json.loads(args.relation.read_text(encoding="utf-8")),
    }
    cases = {name: r["results"] for name, r in reports.items()}
    all_cases = [c for name in ("text", "image", "relation") for c in cases[name]]

    per_scene = {name: scene_metrics(cases[name]) for name in ("text", "image", "relation")}

    # 宏平均:各场景指标简单平均(Hit@1/MRR)
    macro = {
        "hit_rate": {f"@{k}": round(sum(per_scene[n]["hit_rate"][f"@{k}"] for n in per_scene) / 3, 4) for k in TOPK},
        "mrr": round(sum(per_scene[n]["mrr"] for n in per_scene) / 3, 4),
    }

    # 微平均:全部样本合并
    quality = [c for c in all_cases if c["ok"]]
    q = len(quality)
    micro = {
        "count": len(all_cases),
        "quality_sample_count": q,
        "excluded_execution_count": len(all_cases) - q,
        "hit_rate": {f"@{k}": round(sum(1 for c in quality if c["hit"][str(k)]) / max(1, q), 4) for k in TOPK},
        "hit_rate_wilson95": {f"@{k}": [round(x, 4) for x in wilson(sum(1 for c in quality if c["hit"][str(k)]), q)] for k in TOPK},
        "mrr": round(sum(c["mrr"] for c in quality) / max(1, q), 4),
        "latency_p50_ms": pct(sorted(c["latency_ms"] for c in quality), 50),
        "latency_p95_ms": pct(sorted(c["latency_ms"] for c in quality), 95),
    }

    # 超时/空引用分布(按检索状态)
    from collections import Counter
    status_counts = Counter(c["retrieval_status"] for c in all_cases)

    out = {
        "dataset": "industrial_main_r5",
        "total": len(all_cases),
        "scene_counts": {n: len(cases[n]) for n in cases},
        "macro_average": macro,
        "micro_average": micro,
        "per_scene": per_scene,
        "retrieval_status_counts": dict(status_counts),
        "note": "text=fact50+noise40(关image/hypergraph); image=100(关hypergraph); relation=50(全开含hypergraph)。均为 rewrite-on + fusion + retrieval-only。",
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(out, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
