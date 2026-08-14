#!/usr/bin/env python3
"""Build the source-grounded 50-case R5 fact evaluation dataset.

fact 是"普通事实检索",检索目标锚定 FAQ 文档。为避免与 noise 数据集共用同一
FAQ 条目(证据泄漏),本生成器排除 industrial_noise_r3.jsonl 已使用的
canonical_query,并参照 noise 的既定策略按 source_doc 完全分离 tuning/frozen:
- tuning 25 条:仅 steel_metallurgy + petrochemical;
- frozen 25 条:仅 power_energy。

这样 golden_source_ids=[source_doc] 在 tuning/frozen 之间不重叠,通过
evaluation_contract.validate_split_isolation 的 source_id 隔离校验。
"""

import argparse
import hashlib
import json
import random
from collections import Counter, defaultdict
from pathlib import Path

GENERATOR_VERSION = "industrial-fact-r5-template-1"
DEFAULT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_FAQ = DEFAULT_ROOT / "bootstrap/data/faq/industrial_faq.jsonl"
DEFAULT_NOISE = Path(__file__).resolve().parent / "datasets/industrial_noise_r3.jsonl"
DEFAULT_OUT = Path(__file__).resolve().parent / "datasets/industrial_fact_r5.jsonl"

# tuning/frozen 的 source_doc 分配(与 noise 的 source_doc 分离策略对齐)
SPLIT_DOC_TARGETS = {
    "tuning": {"steel_metallurgy": 13, "petrochemical": 12},
    "frozen": {"power_energy": 25},
}


def load_jsonl(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def source_display_path(path: Path) -> str:
    try:
        return path.resolve().relative_to(DEFAULT_ROOT.resolve()).as_posix()
    except ValueError:
        return path.resolve().as_posix()


def load_excluded_questions(noise_path: Path) -> set[str]:
    """读取 noise 数据集已使用的 canonical_query,fact 不得复用。"""
    excluded: set[str] = set()
    for record in load_jsonl(noise_path):
        canonical = record.get("canonical_query")
        if canonical:
            excluded.add(canonical)
    return excluded


def select_balanced(pool: list[dict], count: int, rng: random.Random) -> list[dict]:
    """在指定 source_doc 的候选内按 category 轮询选取,保证类别大致均衡。"""
    by_category: dict[str, list[dict]] = defaultdict(list)
    for item in pool:
        by_category[item["category"]].append(item)
    categories = sorted(by_category)
    for category in categories:
        rng.shuffle(by_category[category])
    indices = {category: 0 for category in categories}
    selected: list[dict] = []
    taken = 0
    while taken < count:
        for category in categories:
            if indices[category] < len(by_category[category]):
                selected.append(by_category[category][indices[category]])
                indices[category] += 1
                taken += 1
                if taken >= count:
                    break
    if taken < count:
        raise ValueError(f"insufficient candidates: need {count}, got {taken}")
    return selected


def build_case(item: dict, split: str, index: int, source_path: Path) -> dict:
    return {
        "schema_version": 1,
        "case_type": "single_turn",
        "id": f"r5-fact-{split}-{index:03d}",
        "query": item["question"],
        "golden_answer": item["answer"],
        "scene": "fact",
        "split": split,
        "ragas_group": "text",
        "expected_channels": ["VECTOR_GLOBAL", "INTENT_DIRECTED"],
        "golden_source_ids": [item["source_doc"]],
        "business_tags": [item["category"]],
        "provenance": {
            "source_file": source_display_path(source_path),
            "source_record_id": item["question"],
        },
        "generator_version": GENERATOR_VERSION,
        "seed": 20260814,
    }


def build_cases(faq: list[dict], noise_path: Path, rng: random.Random,
                source_path: Path = DEFAULT_FAQ) -> list[dict]:
    excluded = load_excluded_questions(noise_path)
    eligible = [item for item in faq if item["question"] not in excluded]

    by_doc: dict[str, list[dict]] = defaultdict(list)
    for item in eligible:
        by_doc[item["source_doc"]].append(item)

    cases: list[dict] = []
    for split in ("tuning", "frozen"):
        targets = SPLIT_DOC_TARGETS[split]
        split_items: list[dict] = []
        for doc, count in targets.items():
            pool = by_doc.get(doc, [])
            split_items.extend(select_balanced(pool, count, rng))
        # 组内 shuffle 让 id 顺序不反映选取顺序
        rng.shuffle(split_items)
        cases.extend(build_case(item, split, index, source_path)
                     for index, item in enumerate(split_items, 1))
    validate_cases(cases)
    return cases


def validate_cases(cases: list[dict]) -> None:
    if len(cases) != 50:
        raise ValueError(f"expected 50 cases, got {len(cases)}")
    split_counts = Counter(case["split"] for case in cases)
    if split_counts != {"tuning": 25, "frozen": 25}:
        raise ValueError(f"invalid split counts: {dict(split_counts)}")

    questions = [case["query"] for case in cases]
    if len(set(questions)) != len(questions):
        raise ValueError("fact questions must be unique")

    # source_doc 精确配比:tuning 13+12、frozen 25
    doc_counts = Counter(case["golden_source_ids"][0] for case in cases)
    expected_docs = {"steel_metallurgy": 13, "petrochemical": 12, "power_energy": 25}
    if doc_counts != expected_docs:
        raise ValueError(f"invalid source_doc counts: {dict(doc_counts)}")

    # source_doc 隔离:tuning 不含 power,frozen 只含 power
    for case in cases:
        doc = case["golden_source_ids"][0]
        if case["split"] == "tuning" and doc == "power_energy":
            raise ValueError(f"tuning case must not use power_energy: {case['id']}")
        if case["split"] == "frozen" and doc != "power_energy":
            raise ValueError(f"frozen case must use power_energy only: {case['id']}")

    for split in ("tuning", "frozen"):
        cats = Counter(case["business_tags"][0] for case in cases if case["split"] == split)
        counts = sorted(cats.values())
        if not counts or max(counts) - min(counts) > 2:
            raise ValueError(f"{split} category distribution too skewed: {dict(cats)}")


def render(cases: list[dict]) -> str:
    return "".join(json.dumps(case, ensure_ascii=False) + "\n" for case in cases)


def manifest(cases: list[dict], source_path: Path, noise_path: Path) -> dict:
    return {
        "generator_version": GENERATOR_VERSION,
        "seed": 20260814,
        "count": len(cases),
        "split_counts": dict(Counter(case["split"] for case in cases)),
        "source_doc_counts": dict(Counter(case["golden_source_ids"][0] for case in cases)),
        "category_counts": dict(Counter(case["business_tags"][0] for case in cases)),
        "source_file": source_display_path(source_path),
        "source_sha256": hashlib.sha256(source_path.read_bytes()).hexdigest(),
        "excluded_noise_file": source_display_path(noise_path),
        "human_evaluation": False,
        "gold_validation": "question/answer/source_doc resolved from FAQ record; source_doc split-isolated from noise",
    }


def outputs(cases: list[dict], out: Path, source_path: Path, noise_path: Path) -> dict[Path, str]:
    return {
        out: render(cases),
        out.with_name(out.stem + "_tuning.jsonl"): render(
            [case for case in cases if case["split"] == "tuning"]),
        out.with_name(out.stem + "_frozen.jsonl"): render(
            [case for case in cases if case["split"] == "frozen"]),
        out.with_suffix(".manifest.json"): json.dumps(
            manifest(cases, source_path, noise_path), ensure_ascii=False, indent=2) + "\n",
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--faq", type=Path, default=DEFAULT_FAQ)
    parser.add_argument("--noise", type=Path, default=DEFAULT_NOISE)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    cases = build_cases(load_jsonl(args.faq), args.noise, random.Random(20260814), args.faq)
    artifacts = outputs(cases, args.out, args.faq, args.noise)
    if args.check:
        for path, expected in artifacts.items():
            if not path.exists() or path.read_text(encoding="utf-8") != expected:
                raise ValueError(f"generated artifact mismatch: {path}")
        print("fact dataset artifacts match deterministic generation")
        return
    for path, content in artifacts.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    print(json.dumps(manifest(cases, args.faq, args.noise), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
