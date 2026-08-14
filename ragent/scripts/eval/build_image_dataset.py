#!/usr/bin/env python3
"""R4-B 工业图像评测集问题生成器。

从 R4-A 登记的素材(descriptions.jsonl)生成 100 条分层问题(40 张图 × 每图 2~3 问)。

关键设计(遵循 §4.3 约束):
- 能力维度四类:device_identification(设备识别)/ component_location(部件定位)/
  parameter_reading(参数读取)/ fault_symptom(故障现象),§4.1 要求;
- query 与 Qwen-VL description 解耦(约束 4「不能从描述模板化反推」):
  * device_identification 用 subcategory 定制措辞(不贴 description,答案即设备类型);
  * 其余三类用人工标注的 image_subject(图片主题短语)作锚点,非描述反推;
- 素材过滤:license 字段非空(素材提供者声明授权,如 Unsplash License/CC0/proprietary)
  且 subcategory、image_subject 齐备的素材进入评测集,缺失 subcategory/image_subject
  报错阻塞;source_url 不再是硬要求(自有素材可留空);
- 按图拆分 split(约束 3):同一图片的所有问题进同一 split,问题数 tuning/frozen 各 50;
- 每图 2~3 问、总数精确 100;能力分配用「最小计数优先」确定性轮询,保证四类各 25。

审核闭环:生成初稿 human_review=true,--finalize 读人工审核文件后输出
human_review=false 的 approved 版本(approve 通过 / reject 移除)。

依赖:仅标准库。素材尚未收集齐 40 张时明确报错。
"""

import argparse
import hashlib
import json
import random
from collections import Counter, defaultdict
from pathlib import Path

import evaluation_contract

GENERATOR_VERSION = "industrial-image-r5-template-1"
DEFAULT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DESCRIPTIONS = DEFAULT_ROOT / "bootstrap/data/images/descriptions.jsonl"
DEFAULT_OUT = Path(__file__).resolve().parent / "datasets/industrial_image_r5.jsonl"

CAPABILITIES = ("device_identification", "component_location",
                "parameter_reading", "fault_symptom")
SUBCATEGORIES = ("engineering_drawing", "scanned_manual", "site_photo")

# 40 张素材 → 100 条问题:每 subcategory 的图数(用于 validate_distribution)
SUBCATEGORY_QUOTA = {
    "engineering_drawing": {"images": 20},
    "scanned_manual": {"images": 10},
    "site_photo": {"images": 10},
}

# split × subcategory 的问数配额(3问图数 / 2问图数)。
# 同时保证三个不变量:
#   1. 每 split 问题数 = 10 张 3-问 + 10 张 2-问 = 50;
#   2. 每 split 图层面 subcategory 分布 10/5/5;
#   3. 全局 3-问图 20 张、2-问图 20 张(每图 2~3 问)。
SPLIT_QUESTION_QUOTA = {
    "tuning": {
        "engineering_drawing": {"three": 5, "two": 5},
        "scanned_manual": {"three": 3, "two": 2},
        "site_photo": {"three": 2, "two": 3},
    },
    "frozen": {
        "engineering_drawing": {"three": 5, "two": 5},
        "scanned_manual": {"three": 2, "two": 3},
        "site_photo": {"three": 3, "two": 2},
    },
}

# device_identification 不贴 subject(答案即设备类型),按 subcategory 定制措辞
DEVICE_IDENTIFICATION_QUERIES = {
    "engineering_drawing": "根据图纸标注，判断图中描绘的核心设备类型",
    "scanned_manual": "根据手册页内容，判断该页说明的设备类型",
    "site_photo": "观察现场照片，识别图中主要的工业设备类型",
}


def load_descriptions(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def source_display_path(path: Path) -> str:
    try:
        return path.resolve().relative_to(DEFAULT_ROOT.resolve()).as_posix()
    except ValueError:
        return path.resolve().as_posix()


def is_license_verified(record: dict) -> bool:
    """素材授权由提供者声明:license 字段非空即视为已声明授权。
    source_url 不再是硬要求(自有/内部素材可留空)。"""
    return bool(record.get("license"))


def select_eligible(descriptions: list[dict]) -> list[dict]:
    """过滤并校验:license 字段非空 + subcategory + image_subject 齐备。

    缺失/非法 subcategory 或 image_subject 时抛错(与 R4-A 登记工具的 warning 定位区分,
    生成阶段这些字段是硬前置)。
    """
    eligible: list[dict] = []
    for record in descriptions:
        if not is_license_verified(record):
            continue
        subcategory = record.get("subcategory")
        if subcategory not in SUBCATEGORIES:
            raise ValueError(
                f"licensed asset missing/invalid subcategory: "
                f"{record.get('image_path')} (subcategory={subcategory!r})")
        if not record.get("image_subject"):
            raise ValueError(
                f"licensed asset missing image_subject: {record.get('image_path')}")
        eligible.append(record)
    return eligible


def validate_distribution(eligible: list[dict]) -> None:
    """校验素材达到 40 张且 subcategory 分层为 20/10/10。"""
    by_sub = Counter(record["subcategory"] for record in eligible)
    expected = {sub: SUBCATEGORY_QUOTA[sub]["images"] for sub in SUBCATEGORIES}
    if dict(by_sub) != expected:
        raise ValueError(
            f"eligible asset distribution must be {expected}, got {dict(by_sub)} "
            f"(total {len(eligible)}); 素材未收集齐 40 张")


def assign_questions_and_split(eligible: list[dict], rng: random.Random) -> tuple[dict[str, int], dict[str, str]]:
    """按配额同时确定每图问数与 split,返回 (question_count, split_of)。

    按 subcategory 分组 shuffle 后,依 SPLIT_QUESTION_QUOTA 精确分配,同时保证
    问题数 50/50 与 split 内 subcategory 10/5/5 均衡。
    """
    by_sub: dict[str, list[dict]] = defaultdict(list)
    for record in eligible:
        by_sub[record["subcategory"]].append(record)
    for sub in SUBCATEGORIES:
        rng.shuffle(by_sub[sub])

    question_count: dict[str, int] = {}
    split_of: dict[str, str] = {}
    for sub in SUBCATEGORIES:
        group = by_sub[sub]
        idx = 0
        for split in ("tuning", "frozen"):
            quota = SPLIT_QUESTION_QUOTA[split][sub]
            for _ in range(quota["three"]):
                image_path = group[idx]["image_path"]
                split_of[image_path] = split
                question_count[image_path] = 3
                idx += 1
            for _ in range(quota["two"]):
                image_path = group[idx]["image_path"]
                split_of[image_path] = split
                question_count[image_path] = 2
                idx += 1
    return question_count, split_of


def choose_capabilities(k: int, counts: Counter, rng: random.Random) -> list[str]:
    """最小计数优先:选当前计数最小的 k 类能力,并列时 rng 打破平局。"""
    ordered = sorted(CAPABILITIES, key=lambda c: (counts[c], rng.random()))
    return ordered[:k]


def query_for(record: dict, capability: str) -> str:
    subject = record["image_subject"]
    if capability == "device_identification":
        return DEVICE_IDENTIFICATION_QUERIES[record["subcategory"]]
    if capability == "component_location":
        return f"请指出图中「{subject}」的关键组成部件"
    if capability == "parameter_reading":
        return f"图中「{subject}」是否有可见的仪表读数、铭牌或操作参数？如有请说明"
    if capability == "fault_symptom":
        return f"图中「{subject}」是否存在异常、磨损或故障迹象"
    raise ValueError(f"unknown capability: {capability}")


def build_case(record: dict, capability: str, split: str, index: int,
               source_path: Path) -> dict:
    image_path = record["image_path"]
    return {
        "schema_version": 1,
        "case_type": "single_turn",
        "id": f"r5-image-{split}-{index:03d}",
        "query": query_for(record, capability),
        "golden_answer": record["description"],
        "scene": "image",
        "split": split,
        "ragas_group": "image",
        "expected_channels": ["IMAGE_SEMANTIC"],
        "golden_source_ids": [image_path],
        "golden_image_paths": [image_path],
        "business_tags": [record["subcategory"], capability],
        "provenance": {
            "source_file": source_display_path(source_path),
            "source_record_id": image_path,
        },
        "human_review": True,
        "generator_version": GENERATOR_VERSION,
        "seed": 20260814,
    }


def build_cases(eligible: list[dict], source_path: Path, rng: random.Random) -> list[dict]:
    validate_distribution(eligible)
    question_count, split_of = assign_questions_and_split(eligible, rng)

    # 能力分配:最小计数优先,保证四类各 25
    capability_counts: Counter = Counter()
    per_image_capabilities: dict[str, list[str]] = {}
    for record in eligible:
        k = question_count[record["image_path"]]
        chosen = choose_capabilities(k, capability_counts, rng)
        per_image_capabilities[record["image_path"]] = chosen
        capability_counts.update(chosen)

    # 生成 cases:同图问题连续,split 内按图分组后编号
    cases: list[dict] = []
    for split in ("tuning", "frozen"):
        split_records = [r for r in eligible if split_of[r["image_path"]] == split]
        index = 1
        for record in split_records:
            for capability in per_image_capabilities[record["image_path"]]:
                cases.append(build_case(record, capability, split, index, source_path))
                index += 1
    validate_cases(cases)
    return cases


def validate_cases(cases: list[dict]) -> None:
    if len(cases) != 100:
        raise ValueError(f"expected 100 cases, got {len(cases)}")

    split_counts = Counter(case["split"] for case in cases)
    if split_counts["tuning"] != 50 or split_counts["frozen"] != 50:
        raise ValueError(f"split question counts must be 50/50, got {dict(split_counts)}")

    capability_counts = Counter(case["business_tags"][1] for case in cases)
    for capability in CAPABILITIES:
        if capability_counts[capability] != 25:
            raise ValueError(
                f"capability {capability} expected 25 cases, got {capability_counts[capability]}")

    # 同图问题不跨 split
    image_split: dict[str, str] = {}
    for case in cases:
        image_path = case["golden_image_paths"][0]
        if image_path in image_split and image_split[image_path] != case["split"]:
            raise ValueError(f"image {image_path} spans multiple splits")
        image_split[image_path] = case["split"]

    # 每图 2~3 问
    image_counts = Counter(case["golden_image_paths"][0] for case in cases)
    for image_path, count in image_counts.items():
        if count not in (2, 3):
            raise ValueError(f"image {image_path} has {count} questions (must be 2~3)")

    # 每 split 内 subcategory 分布均衡(图层面 10/5/5)
    for split in ("tuning", "frozen"):
        split_images = {case["golden_image_paths"][0] for case in cases if case["split"] == split}
        sub_counts = Counter()
        for case in cases:
            if case["split"] == split:
                sub_counts[case["business_tags"][0]] += 1
        # 注意 sub_counts 是问题数(非图数),每 split 各 50 问;这里校验图数分布
        image_sub_counts = Counter()
        for image_path in split_images:
            for case in cases:
                if case["golden_image_paths"][0] == image_path:
                    image_sub_counts[case["business_tags"][0]] += 1
                    break
        expected_images = {"engineering_drawing": 10, "scanned_manual": 5, "site_photo": 5}
        if dict(image_sub_counts) != expected_images:
            raise ValueError(
                f"split {split} subcategory image counts must be {expected_images}, "
                f"got {dict(image_sub_counts)}")

    evaluation_contract.validate_records(cases)


def render(cases: list[dict]) -> str:
    return "".join(json.dumps(case, ensure_ascii=False) + "\n" for case in cases)


def build_manifest(cases: list[dict], source_path: Path) -> dict:
    split_counts = Counter(case["split"] for case in cases)
    capability_counts = Counter(case["business_tags"][1] for case in cases)
    subcategory_counts = Counter(case["business_tags"][0] for case in cases)
    return {
        "generator_version": GENERATOR_VERSION,
        "seed": 20260814,
        "count": len(cases),
        "split_counts": dict(split_counts),
        "capability_counts": dict(capability_counts),
        "subcategory_counts": dict(subcategory_counts),
        "source_file": source_display_path(source_path),
        "source_sha256": hashlib.sha256(source_path.read_bytes()).hexdigest(),
        "sha256": hashlib.sha256(render(cases).encode("utf-8")).hexdigest(),
        "human_review_pending": sum(1 for c in cases if c.get("human_review")),
        "golden_answer_note": "golden_answer 为素材 description 全文,仅作评测 reference,不构成可被 query 反推的证据",
    }


def load_review(path: Path) -> dict[str, dict]:
    with path.open(encoding="utf-8") as handle:
        entries = json.load(handle)
    return {entry["id"]: entry for entry in entries}


def finalize(cases: list[dict], review: dict[str, dict]) -> tuple[list[dict], int]:
    """按人工审核文件回写 human_review 状态,reject 的条目移除。

    返回 (finalized_cases, rejected_count)。reject 后数量不足 100 由调用方提示。
    """
    case_ids = {case["id"] for case in cases}
    missing = case_ids - set(review)
    if missing:
        raise ValueError(f"review missing decisions for {len(missing)} cases: "
                         f"{sorted(missing)[:5]}...")
    finalized = []
    rejected = 0
    for case in cases:
        decision = review[case["id"]]
        action = decision["action"]
        if action == "approve":
            finalized.append({**case, "human_review": False,
                              "review_note": decision.get("note", "")})
        elif action == "reject":
            rejected += 1
            continue
        else:
            raise ValueError(f"unknown review action for {case['id']}: {action}")
    return finalized, rejected


def main() -> None:
    parser = argparse.ArgumentParser(description="R4-B industrial image dataset generator")
    parser.add_argument("--descriptions", type=Path, default=DEFAULT_DESCRIPTIONS)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--finalize", type=Path, default=None,
                        help="人工审核文件(JSON 数组 [{id, action: approve|reject, note}]),"
                             "回写 human_review 并输出 approved 版本")
    args = parser.parse_args()

    descriptions = load_descriptions(args.descriptions)
    eligible = select_eligible(descriptions)
    cases = build_cases(eligible, args.descriptions, random.Random(20260814))

    if args.finalize:
        review = load_review(args.finalize)
        cases, rejected = finalize(cases, review)
        args.out = args.out.with_name(args.out.stem + "_approved.jsonl")
        if rejected > 0:
            print(f"[warn] reject {rejected} 条,最终 {len(cases)} 条 < 100,需人工补充问题")

    text = render(cases)
    manifest = build_manifest(cases, args.descriptions)
    manifest_path = args.out.with_suffix(".manifest.json")
    manifest_text = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"

    if args.check:
        if not args.out.exists() or args.out.read_text(encoding="utf-8") != text:
            raise ValueError(f"dataset does not match deterministic generation: {args.out}")
        print("image dataset matches deterministic generation")
        return

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(text, encoding="utf-8")
    manifest_path.write_text(manifest_text, encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
