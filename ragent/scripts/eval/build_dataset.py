#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
构建工业 FAQ 评测集(Phase 8, 8.1)

从 Phase5 生产数据集 FAQ(ragent/bootstrap/data/faq/industrial_faq.jsonl)中
按分类分层抽样,生成 {query, golden_answer, scene} 评测集。

用法:
    python build_dataset.py [--faq <path>] [--per-category N] [--seed 42] [--out <path>]
"""
import argparse
import json
import random
from collections import defaultdict
from pathlib import Path

DEFAULT_FAQ = Path(__file__).resolve().parents[2] / "bootstrap/data/faq/industrial_faq.jsonl"
DEFAULT_OUT = Path(__file__).resolve().parent / "datasets/industrial_eval.jsonl"


def load_faq(path: Path):
    items = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            items.append(json.loads(line))
    return items


def build(items, per_category, seed):
    rng = random.Random(seed)
    by_cat = defaultdict(list)
    for it in items:
        by_cat[it.get("category", "其他")].append(it)
    selected = []
    for cat, lst in sorted(by_cat.items()):
        n = min(per_category, len(lst))
        selected.extend(rng.sample(lst, n))
    rng.shuffle(selected)
    return selected


def main():
    parser = argparse.ArgumentParser(description="构建工业 FAQ 评测集")
    parser.add_argument("--faq", type=Path, default=DEFAULT_FAQ, help="FAQ jsonl 路径")
    parser.add_argument("--per-category", type=int, default=12, help="每个分类抽取条数")
    parser.add_argument("--seed", type=int, default=42, help="随机种子")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="评测集输出路径")
    args = parser.parse_args()

    items = load_faq(args.faq)
    print(f"[build_dataset] 读取 FAQ 共 {len(items)} 条")
    selected = build(items, args.per_category, args.seed)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        for it in selected:
            f.write(json.dumps({
                "query": it["question"],
                "golden_answer": it["answer"],
                "scene": it.get("category", "其他"),
            }, ensure_ascii=False) + "\n")
    print(f"[build_dataset] 评测集生成: {args.out} 共 {len(selected)} 条")


if __name__ == "__main__":
    main()
