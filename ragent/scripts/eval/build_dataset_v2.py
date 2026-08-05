#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Build a reproducible, source-grounded industrial retrieval evaluation set.

The generator intentionally does not call an LLM or perform human scoring. It
creates deterministic query variants from repository data and keeps the source
record identifiers needed for automatic validation and channel-level A/B tests.
"""

import argparse
import json
import random
from pathlib import Path
from typing import Iterable

GENERATOR_VERSION = "industrial-eval-v2-template-1"
DEFAULT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_FAQ = DEFAULT_ROOT / "bootstrap/data/faq/industrial_faq.jsonl"
DEFAULT_IMAGES = DEFAULT_ROOT / "bootstrap/data/images/descriptions.jsonl"
DEFAULT_HYPEREDGES = DEFAULT_ROOT / "bootstrap/data/hypergraph/hyperedges.jsonl"
DEFAULT_OUT = Path(__file__).resolve().parent / "datasets/industrial_eval_v2.jsonl"


def load_jsonl(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def shuffled(items: Iterable[dict], rng: random.Random) -> list[dict]:
    result = list(items)
    rng.shuffle(result)
    return result


def source_ref(path: Path, record_id: str | None = None) -> dict:
    try:
        display_path = path.resolve().relative_to(DEFAULT_ROOT.resolve()).as_posix()
    except ValueError:
        display_path = path.as_posix()
    ref = {"source_file": display_path}
    if record_id is not None:
        ref["source_record_id"] = record_id
    return ref


def faq_item(item: dict, query: str, scene: str, index: int, source_path: Path) -> dict:
    return {
        "id": f"v2-{scene}-{index:03d}",
        "query": query,
        "golden_answer": item["answer"],
        "scene": scene,
        "expected_channels": ["VECTOR_GLOBAL", "INTENT_DIRECTED"],
        "golden_source_ids": [item["source_doc"]],
        "provenance": source_ref(source_path, item["question"]),
    }


def build_faq_cases(faq: list[dict], source_path: Path, rng: random.Random) -> list[dict]:
    selected = shuffled(faq, rng)[:50]
    factual = [faq_item(item, item["question"], "fact", i + 1, source_path)
               for i, item in enumerate(selected[:25])]

    colloquial_templates = (
        "师傅现场问：{}",
        "用大白话说，{}",
        "我只知道这个设备出了问题，帮我查一下：{}",
        "现场比较急，想确认一下：{}",
        "把这个工业问题按排查步骤讲清楚：{}",
    )
    colloquial = []
    for i, item in enumerate(selected[25:], 1):
        template = colloquial_templates[(i - 1) % len(colloquial_templates)]
        colloquial.append(faq_item(item, template.format(item["question"]),
                                   "colloquial", i, source_path))
        colloquial[-1]["origin_query"] = item["question"]
    return factual + colloquial


def image_clue(description: str) -> str:
    return description.split("。", 1)[0].strip()[:120]


def build_image_cases(images: list[dict], source_path: Path, rng: random.Random) -> list[dict]:
    ordered = shuffled(images, rng)
    templates = (
        "请判断这张工业图像对应的设备或场景：{}",
        "从图像语义看，下面这个工业现场属于什么设备或工艺：{}",
        "检索与该图纸或现场相关的设备说明，图像线索是：{}",
        "该图像最可能涉及哪类设备与作业环境？线索：{}",
    )
    cases = []
    for i in range(25):
        item = ordered[i % len(ordered)]
        query = templates[i % len(templates)].format(image_clue(item["description"]))
        cases.append({
            "id": f"v2-image-{i + 1:03d}",
            "query": query,
            "golden_answer": item["description"],
            "scene": "image",
            "expected_channels": ["IMAGE_SEMANTIC"],
            "golden_source_ids": [item["image_path"]],
            "golden_image_paths": [item["image_path"]],
            "provenance": source_ref(source_path, item["image_path"]),
        })
    return cases


def relation_answer(edge: dict) -> str:
    parts = [edge["equipment"]]
    if edge.get("condition"):
        parts.append(f"在{edge['condition']}条件下")
    if edge.get("parameter"):
        parts.append(f"因{edge['parameter']}异常")
    if edge.get("fault"):
        parts.append(f"导致{edge['fault']}")
    for entity in (edge.get("extendedEntities") or [])[:2]:
        parts.append(f"{entity.get('label', '')}:{entity.get('value', '')}")
    return "，".join(part for part in parts if part)


def build_relation_cases(edges: list[dict], source_path: Path, rng: random.Random) -> list[dict]:
    candidates = [edge for edge in edges
                  if edge.get("equipment") and (edge.get("parameter") or edge.get("fault"))]
    ordered = shuffled(candidates, rng)[:25]
    cases = []
    for i, edge in enumerate(ordered, 1):
        condition = edge.get("condition") or "当前工况"
        parameter = edge.get("parameter") or "相关参数"
        fault = edge.get("fault") or "对应故障"
        query = (f"在{condition}下，{edge['equipment']}的{parameter}异常，"
                 f"与{fault}有什么关系，应该如何判断？")
        cases.append({
            "id": f"v2-relation-{i:03d}",
            "query": query,
            "golden_answer": relation_answer(edge),
            "scene": "relation",
            "expected_channels": ["HYPERGRAPH"],
            "golden_source_ids": [edge["edgeId"]],
            "golden_hyperedge_ids": [edge["edgeId"]],
            "provenance": source_ref(source_path, edge["edgeId"]),
        })
    return cases


def validate(cases: list[dict], faq: list[dict], images: list[dict], edges: list[dict]) -> None:
    if len(cases) != 100:
        raise ValueError(f"expected 100 cases, got {len(cases)}")
    counts = {scene: sum(item["scene"] == scene for item in cases)
              for scene in ("fact", "colloquial", "image", "relation")}
    if counts != {scene: 25 for scene in counts}:
        raise ValueError(f"scene balance check failed: {counts}")

    faq_questions = {item["question"] for item in faq}
    image_paths = {item["image_path"] for item in images}
    edge_ids = {item["edgeId"] for item in edges}
    for item in cases:
        ref = item["provenance"]["source_record_id"]
        if item["scene"] in ("fact", "colloquial") and ref not in faq_questions:
            raise ValueError(f"FAQ provenance not found: {ref}")
        if item["scene"] == "image" and ref not in image_paths:
            raise ValueError(f"image provenance not found: {ref}")
        if item["scene"] == "relation" and ref not in edge_ids:
            raise ValueError(f"hyperedge provenance not found: {ref}")


def render_dataset(cases: list[dict], seed: int) -> str:
    rendered_cases = []
    for case in cases:
        rendered_case = {**case, "generator_version": GENERATOR_VERSION, "seed": seed}
        rendered_cases.append(json.dumps(rendered_case, ensure_ascii=False))
    return "\n".join(rendered_cases) + "\n"


def build_manifest(cases: list[dict], faq_path: Path, image_path: Path, hyperedge_path: Path, seed: int) -> dict:
    return {
        "generator_version": GENERATOR_VERSION,
        "seed": seed,
        "count": len(cases),
        "scene_counts": {scene: sum(item["scene"] == scene for item in cases)
                         for scene in ("fact", "colloquial", "image", "relation")},
        "source_files": [
            source_ref(faq_path)["source_file"],
            source_ref(image_path)["source_file"],
            source_ref(hyperedge_path)["source_file"],
        ],
        "human_evaluation": False,
        "gold_validation": "source_record_id existence and scene balance",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="build industrial_eval_v2.jsonl")
    parser.add_argument("--faq", type=Path, default=DEFAULT_FAQ)
    parser.add_argument("--images", type=Path, default=DEFAULT_IMAGES)
    parser.add_argument("--hyperedges", type=Path, default=DEFAULT_HYPEREDGES)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--seed", type=int, default=202603)
    parser.add_argument("--check", action="store_true",
                        help="verify the checked-in dataset and manifest match deterministic generation")
    args = parser.parse_args()

    faq = load_jsonl(args.faq)
    images = load_jsonl(args.images)
    edges = load_jsonl(args.hyperedges)
    rng = random.Random(args.seed)
    cases = build_faq_cases(faq, args.faq, rng)
    cases.extend(build_image_cases(images, args.images, rng))
    cases.extend(build_relation_cases(edges, args.hyperedges, rng))
    validate(cases, faq, images, edges)

    dataset_text = render_dataset(cases, args.seed)
    manifest = build_manifest(cases, args.faq, args.images, args.hyperedges, args.seed)
    manifest_path = args.out.with_suffix(".manifest.json")
    manifest_text = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    if args.check:
        if not args.out.exists() or not manifest_path.exists():
            raise FileNotFoundError("dataset or manifest is missing; run the generator without --check first")
        if args.out.read_text(encoding="utf-8") != dataset_text:
            raise ValueError(f"dataset does not match deterministic generation: {args.out}")
        if manifest_path.read_text(encoding="utf-8") != manifest_text:
            raise ValueError(f"manifest does not match deterministic generation: {manifest_path}")
        print("dataset and manifest match deterministic generation")
        return

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(dataset_text, encoding="utf-8")
    manifest_path.write_text(manifest_text, encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
