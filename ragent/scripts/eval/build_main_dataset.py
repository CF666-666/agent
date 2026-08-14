#!/usr/bin/env python3
"""Merge the four single-turn subsets into the 240-case R5 main dataset.

主集 = fact 50 + noise 40 + image 100 + relation 50,四个子集按固定顺序
(fact → noise → image → relation)拼接,子集内保持文件原序,保证输出字节级稳定。

- 每条记录原样保留 split 字段,供后续从主集拆分 frozen 子集做严格 A/B;
- 场景分类:ragas_group==noise → noise,scene∈{fact,image,relation} → 对应场景,
  其余(如 scene=colloquial 但非 noise)显式报错;
- 校验:子集条数、场景分布、全局 id 唯一、跨子集规范问题不重复、子集内证据键不重复;
- manifest 记录场景分布、split 分布、各子集 SHA-256 与整体 SHA-256。

image 子集(industrial_image_r5.jsonl)由 R4 生成,当前缺失时明确报错。
"""

import argparse
import hashlib
import json
import re
from collections import Counter
from pathlib import Path

import evaluation_contract

GENERATOR_VERSION = "industrial-main-r5-merge-1"
SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_OUT = SCRIPT_DIR / "datasets/industrial_main_r5.jsonl"

SUBSET_ORDER = ("fact", "noise", "image", "relation")
EXPECTED_COUNTS = {"fact": 50, "noise": 40, "image": 100, "relation": 50}

DEFAULT_SUBSETS = {
    "fact": SCRIPT_DIR / "datasets/industrial_fact_r5.jsonl",
    "noise": SCRIPT_DIR / "datasets/industrial_noise_r3.jsonl",
    "image": SCRIPT_DIR / "datasets/industrial_image_r5.jsonl",
    "relation": SCRIPT_DIR / "datasets/industrial_relation_r2.jsonl",
}


def load_jsonl(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def normalized_query(value: str) -> str:
    return re.sub(r"[\s，。！？：；、,.!?:;]", "", value).lower()


def scene_of(record: dict) -> str:
    """主集场景分类:fact / noise / image / relation。"""
    if record.get("ragas_group") == "noise":
        return "noise"
    scene = record["scene"]
    if scene in ("fact", "image", "relation"):
        return scene
    raise ValueError(
        f"unsupported scene in main dataset: {scene} "
        f"(record {record.get('id')}); colloquial records must set ragas_group=noise")


def canonical_questions(records: list[dict]) -> set[str]:
    """规范问题集:优先 canonical_query,回退 query。用于跨子集去重。"""
    return {record.get("canonical_query") or record["query"] for record in records}


def validate_subsets(subsets: dict[str, list[dict]]) -> None:
    for name, records in subsets.items():
        expected = EXPECTED_COUNTS[name]
        if len(records) != expected:
            raise ValueError(f"{name} subset expected {expected} records, got {len(records)}")
        evaluation_contract.validate_records(records)

    # 跨子集规范问题不重复(fact/noise 都锚定 FAQ question,是唯一真实重叠点)
    seen: dict[str, str] = {}
    for name in SUBSET_ORDER:
        for question in canonical_questions(subsets[name]):
            key = normalized_query(question)
            if key in seen:
                raise ValueError(
                    f"canonical question overlap between {seen[key]} and {name}: {question}")
            seen[key] = name

    # 子集内记录级证据键不重复(source_id 是文档级,同一文档多条记录属正常,不参与去重)
    for name in SUBSET_ORDER:
        evidence_seen: set[str] = set()
        for record in subsets[name]:
            for key in evaluation_contract.evidence_keys(record):
                if key.startswith("source_id:"):
                    continue
                if key in evidence_seen:
                    raise ValueError(
                        f"duplicate evidence key in {name} subset: {key} "
                        f"(record {record['id']})")
                evidence_seen.add(key)


def merge_records(subsets: dict[str, list[dict]]) -> list[dict]:
    merged: list[dict] = []
    for name in SUBSET_ORDER:
        merged.extend(subsets[name])
    return merged


def validate_merged(records: list[dict]) -> None:
    scene_counts = Counter(scene_of(record) for record in records)
    if scene_counts != EXPECTED_COUNTS:
        raise ValueError(f"invalid scene counts: {dict(scene_counts)}")

    ids = [record["id"] for record in records]
    if len(set(ids)) != len(ids):
        raise ValueError("duplicate id in merged dataset")


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def render(records: list[dict]) -> str:
    return "".join(json.dumps(record, ensure_ascii=False) + "\n" for record in records)


def build_manifest(records: list[dict], subset_paths: dict[str, Path]) -> dict:
    scene_counts = Counter(scene_of(record) for record in records)
    split_counts = Counter(record["split"] for record in records)
    return {
        "generator_version": GENERATOR_VERSION,
        "count": len(records),
        "scene_counts": dict(scene_counts),
        "split_counts": dict(split_counts),
        "subset_sha256": {
            name: hashlib.sha256(path.read_bytes()).hexdigest()
            for name, path in subset_paths.items()
        },
        "sha256": sha256_text(render(records)),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="merge R5 single-turn main dataset")
    parser.add_argument("--fact", type=Path, default=DEFAULT_SUBSETS["fact"])
    parser.add_argument("--noise", type=Path, default=DEFAULT_SUBSETS["noise"])
    parser.add_argument("--image", type=Path, default=DEFAULT_SUBSETS["image"])
    parser.add_argument("--relation", type=Path, default=DEFAULT_SUBSETS["relation"])
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    subset_paths = {
        "fact": args.fact,
        "noise": args.noise,
        "image": args.image,
        "relation": args.relation,
    }
    for name, path in subset_paths.items():
        if not path.exists():
            raise FileNotFoundError(
                f"{name} subset missing: {path}"
                f"{' (image 子集待 R4 生成)' if name == 'image' else ''}")

    subsets = {name: load_jsonl(subset_paths[name]) for name in SUBSET_ORDER}
    validate_subsets(subsets)
    merged = merge_records(subsets)
    validate_merged(merged)

    text = render(merged)
    manifest = build_manifest(merged, subset_paths)
    manifest_path = args.out.with_suffix(".manifest.json")
    manifest_text = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"

    if args.check:
        if not args.out.exists() or args.out.read_text(encoding="utf-8") != text:
            raise ValueError(f"merged dataset does not match deterministic generation: {args.out}")
        if not manifest_path.exists() or manifest_path.read_text(encoding="utf-8") != manifest_text:
            raise ValueError(f"manifest does not match deterministic generation: {manifest_path}")
        print("main dataset and manifest match deterministic merge")
        return

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(text, encoding="utf-8")
    manifest_path.write_text(manifest_text, encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
