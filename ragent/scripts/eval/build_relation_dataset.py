#!/usr/bin/env python3
"""Build the source-grounded 50-case R2 relation evaluation dataset."""

import argparse
import hashlib
import json
import random
from collections import defaultdict
from pathlib import Path

GENERATOR_VERSION = "industrial-relation-r2-template-1"
DEFAULT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_HYPEREDGES = DEFAULT_ROOT / "bootstrap/data/hypergraph/hyperedges.jsonl"
DEFAULT_OUT = Path(__file__).resolve().parent / "datasets/industrial_relation_r2.jsonl"
QUERY_ALIASES = {
    "脱硫塔循环泵": "脱硫循环泵",
    "发电机定子绕组": "发电机定子",
    "氧化风机": "氧化风",
    "浆液循环泵": "浆循泵",
}


def load_jsonl(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def edge_entities(edge: dict) -> set[str]:
    values = {edge.get("equipment"), edge.get("condition"), edge.get("parameter"), edge.get("fault")}
    values.update(item.get("value") for item in (edge.get("extendedEntities") or []))
    return {str(value).strip() for value in values if value and str(value).strip()}


def relation_answer(edge: dict) -> str:
    parts = [edge["equipment"]]
    if edge.get("condition"):
        parts.append(f"工况:{edge['condition']}")
    if edge.get("parameter"):
        parts.append(f"参数:{edge['parameter']}")
    if edge.get("fault"):
        parts.append(f"故障:{edge['fault']}")
    parts.extend(
        f"{item.get('label', '')}:{item.get('value', '')}"
        for item in (edge.get("extendedEntities") or [])[:2]
        if item.get("value")
    )
    return "，".join(parts)


def common_entity(first: dict, second: dict) -> str | None:
    shared = edge_entities(first) & edge_entities(second)
    if not shared:
        return None
    equipment = first.get("equipment")
    return equipment if equipment in shared else sorted(shared, key=lambda value: (-len(value), value))[0]


def select_two_hop_pairs(candidates: list[dict], rng: random.Random, count: int) -> list[tuple[dict, dict, str]]:
    by_equipment = defaultdict(list)
    for edge in candidates:
        by_equipment[edge["equipment"]].append(edge)
    groups = list(by_equipment.items())
    rng.shuffle(groups)
    used: set[str] = set()
    pairs = []
    for equipment, group in groups:
        options = list(group)
        rng.shuffle(options)
        available = [edge for edge in options if edge["edgeId"] not in used]
        if len(available) < 2:
            continue
        first, second = available[:2]
        bridge = common_entity(first, second)
        if bridge is None:
            continue
        pairs.append((first, second, bridge))
        used.update((first["edgeId"], second["edgeId"]))
        if len(pairs) == count:
            return pairs
    raise ValueError(f"not enough isolated two-hop pairs: expected {count}, got {len(pairs)}")


def build_case(edges: list[dict], index: int, split: str, source_path: Path,
               bridge: str | None = None) -> dict:
    first = edges[0]
    edge_ids = [edge["edgeId"] for edge in edges]
    source_documents = sorted({edge["sourceDocument"] for edge in edges})
    if len(edges) == 1:
        parameter = first.get("parameter") or "相关参数"
        fault = first.get("fault") or "对应故障"
        query = f"{first['equipment']}的{parameter}异常与{fault}有什么关系，应该如何排查？"
        tags = ["single_edge"]
    else:
        second = edges[1]
        left = first.get("parameter") or first.get("fault") or "异常现象"
        right = second.get("fault") or second.get("parameter") or "处置要求"
        query = f"以{bridge}为关联，{left}与{right}之间有什么关系，请给出完整关系路径？"
        tags = ["two_hop"]
    return {
        "schema_version": 1,
        "case_type": "single_turn",
        "id": f"r2-relation-{split}-{index:03d}",
        "query": query,
        "golden_answer": "；".join(relation_answer(edge) for edge in edges),
        "scene": "relation",
        "split": split,
        "expected_channels": ["HYPERGRAPH"],
        "golden_source_ids": edge_ids,
        "golden_hyperedge_ids": edge_ids,
        "golden_source_documents": source_documents,
        "business_tags": tags,
        "provenance": {
            "source_file": source_path.resolve().relative_to(DEFAULT_ROOT.resolve()).as_posix(),
            "source_record_id": edge_ids[0],
        },
        "generator_version": GENERATOR_VERSION,
        "seed": 20260813,
    }


def apply_query_variants(cases: list[dict]) -> None:
    """Add auditable query-only difficulty without changing source-derived goldens."""
    for case in cases:
        query = case["query"]
        for canonical, alias in QUERY_ALIASES.items():
            if canonical in query:
                case["query"] = query.replace(canonical, alias)
                case["business_tags"].append("alias_variant")
                break

    distractor_candidates = [
        case for case in cases
        if case["split"] == "frozen" and "single_edge" in case["business_tags"]
    ][:4]
    for case in distractor_candidates:
        case["query"] += "（注意不要与同类设备的相似故障混淆）"
        case["business_tags"].append("similar_entity_distractor")


def build_cases(edges: list[dict], rng: random.Random,
                source_path: Path = DEFAULT_HYPEREDGES) -> list[dict]:
    candidates = [edge for edge in edges if edge.get("edgeId") and edge.get("equipment")
                  and edge.get("sourceDocument") and (edge.get("parameter") or edge.get("fault"))]
    pairs = select_two_hop_pairs(candidates, rng, 10)
    used = {edge["edgeId"] for pair in pairs for edge in pair[:2]}
    available_singles = [edge for edge in candidates if edge["edgeId"] not in used]
    preferred = []
    for canonical in QUERY_ALIASES:
        match = next((edge for edge in available_singles
                      if canonical in edge_entities(edge)
                      and edge["edgeId"] not in {item["edgeId"] for item in preferred}), None)
        if match is not None:
            preferred.append(match)
    remaining = [edge for edge in available_singles
                 if edge["edgeId"] not in {item["edgeId"] for item in preferred}]
    rng.shuffle(remaining)
    singles = preferred + remaining[:40 - len(preferred)]
    if len(singles) != 40:
        raise ValueError("not enough isolated single-edge candidates")

    cases = []
    for split_index, split in enumerate(("tuning", "frozen")):
        if split == "tuning":
            split_singles = singles[:20]
        else:
            split_singles = singles[20:40]
        split_pairs = pairs[split_index * 5:(split_index + 1) * 5]
        specs = [([edge], None) for edge in split_singles]
        specs.extend(([first, second], bridge) for first, second, bridge in split_pairs)
        rng.shuffle(specs)
        cases.extend(build_case(case_edges, index, split, source_path, bridge)
                     for index, (case_edges, bridge) in enumerate(specs, 1))
    apply_query_variants(cases)
    return cases


def render(cases: list[dict]) -> str:
    return "".join(json.dumps(case, ensure_ascii=False) + "\n" for case in cases)


def manifest(cases: list[dict], source_path: Path) -> dict:
    source_hash = hashlib.sha256(source_path.read_bytes()).hexdigest()
    return {
        "generator_version": GENERATOR_VERSION,
        "seed": 20260813,
        "count": len(cases),
        "split_counts": {split: sum(case["split"] == split for case in cases)
                         for split in ("tuning", "frozen")},
        "path_counts": {tag: sum(tag in case["business_tags"] for case in cases)
                        for tag in ("single_edge", "two_hop")},
        "source_file": source_path.resolve().relative_to(DEFAULT_ROOT.resolve()).as_posix(),
        "source_sha256": source_hash,
        "human_evaluation": False,
        "gold_validation": "hyperedge IDs and source documents derived from source records",
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--hyperedges", type=Path, default=DEFAULT_HYPEREDGES)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    cases = build_cases(load_jsonl(args.hyperedges), random.Random(20260813), args.hyperedges)
    outputs = {
        args.out: render(cases),
        args.out.with_name(args.out.stem + "_tuning.jsonl"): render(
            [case for case in cases if case["split"] == "tuning"]),
        args.out.with_name(args.out.stem + "_frozen.jsonl"): render(
            [case for case in cases if case["split"] == "frozen"]),
        args.out.with_suffix(".manifest.json"): json.dumps(
            manifest(cases, args.hyperedges), ensure_ascii=False, indent=2) + "\n",
    }
    if args.check:
        for path, expected in outputs.items():
            if not path.exists() or path.read_text(encoding="utf-8") != expected:
                raise ValueError(f"generated artifact mismatch: {path}")
        print("relation dataset artifacts match deterministic generation")
        return
    for path, content in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    print(json.dumps(manifest(cases, args.hyperedges), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
