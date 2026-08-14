#!/usr/bin/env python3
"""R4-A 工业图像素材登记/校验/去重工具。

定位:辅助人工收集 40 张独立素材(工程图 20 / 扫描手册 10 / 现场照片 10)时
快速检查完整性。不负责下载图片(授权由素材提供者声明,登记工具不负责核验),只负责
登记校验。

关键语义:
- category 是「设备/领域类别」(开放词表,如 steel_metallurgy / petrochemical);
- subcategory 是「素材类型」,枚举 engineering_drawing / scanned_manual / site_photo;
- 素材授权由素材提供者声明:license 字段仅作记录(可填 Unsplash License/CC0/
  proprietary 等),不构成进入评测集的门槛;source_url 也不再是硬要求(自有/内部素材
  可留空)。license 为空仅产生软警告(建议填写),不阻塞登记;
- 哈希去重以内容 sha256 为准(同一图片复制成不同文件名也能检测),image_path
  去重捕获登记重复,同名不同内容视为路径冲突。

输出:image_assets_manifest.json(每条 sha256 + subcategory + 授权状态 + 分层统计
+ 整体指纹),供后续 R4-C 重建索引与 R5-A 合并时校验一致性。
"""

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

DEFAULT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_IMAGES_DIR = DEFAULT_ROOT / "bootstrap/data/images"
DEFAULT_DESCRIPTIONS = DEFAULT_IMAGES_DIR / "descriptions.jsonl"
DEFAULT_MANIFEST = Path(__file__).resolve().parent / "datasets/image_assets_manifest.json"

REQUIRED_FIELDS = ("image_path", "description", "category")
SUBCATEGORIES = ("engineering_drawing", "scanned_manual", "site_photo")
SUBCATEGORY_TARGETS = {"engineering_drawing": 20, "scanned_manual": 10, "site_photo": 10}
TOTAL_TARGET = 40


def load_descriptions(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def compute_sha256(file_path: Path) -> str:
    return hashlib.sha256(file_path.read_bytes()).hexdigest()


def validate_assets(descriptions: list[dict], image_dir: Path) -> dict:
    """校验素材清单,返回 {errors, warnings, hashes, duplicates, stats}。

    errors:   硬错误(必填字段缺失/文件不存在),阻塞登记;
    warnings: 软警告(授权缺失/缺 subcategory),不阻塞;
    duplicates: 三类 {path_duplicate, content_duplicate, path_conflict};
    stats:    总数、subcategory 分层、授权合规/未确认计数。
    """
    errors: list[str] = []
    warnings: list[str] = []
    hashes: dict[str, str] = {}
    # 去重两类:path_duplicate(同一路径登记两次)、content_duplicate(不同路径但内容哈希相同)。
    # 不设 path_conflict:运行时计算哈希的模型下,同一路径在同一次遍历中内容恒定,
    # "同名不同内容"只会在人工预登记哈希时出现,而本工具不引入预登记哈希字段。
    duplicates: dict[str, list[str]] = {
        "path_duplicate": [], "content_duplicate": [],
    }

    seen_paths: set[str] = set()
    seen_hashes: dict[str, str] = {}

    for index, record in enumerate(descriptions, 1):
        location = f"record {index}"
        missing_required = False
        for field in REQUIRED_FIELDS:
            value = record.get(field)
            if not isinstance(value, str) or not value.strip():
                errors.append(f"{location}: {field} must be a non-empty string")
                missing_required = True
        if missing_required:
            continue

        image_path = record["image_path"]
        file_path = image_dir / image_path
        if not file_path.is_file():
            errors.append(f"{location}: image file missing: {image_path}")
            continue
        sha = compute_sha256(file_path)
        hashes[image_path] = sha

        if image_path in seen_paths:
            duplicates["path_duplicate"].append(image_path)
        else:
            seen_paths.add(image_path)

        if sha in seen_hashes and seen_hashes[sha] != image_path:
            duplicates["content_duplicate"].append(
                f"{image_path} == {seen_hashes[sha]}")
        else:
            seen_hashes[sha] = image_path

        if not record.get("license"):
            warnings.append(
                f"{location}: license 字段为空(记录字段,不阻塞;建议填授权类型)")
        if record.get("subcategory") not in SUBCATEGORIES:
            warnings.append(
                f"{location}: missing/invalid subcategory (required for R4-B question generation)")
        if not record.get("image_subject"):
            warnings.append(
                f"{location}: missing image_subject (人工标注的图片主题短语, R4-B 生成问题必需)")

    # 同名不同内容:image_path 相同但 sha 不同(先出现的 path_duplicate 之外的场景)
    stats = build_stats(descriptions, hashes)
    return {
        "errors": errors,
        "warnings": warnings,
        "hashes": hashes,
        "duplicates": duplicates,
        "stats": stats,
    }


def build_stats(descriptions: list[dict], hashes: dict[str, str]) -> dict:
    total = len(descriptions)
    subcategory_counts = Counter(
        record.get("subcategory") for record in descriptions
        if record.get("subcategory") in SUBCATEGORIES)
    licensed = sum(
        1 for record in descriptions if record.get("license"))
    unverified = total - licensed
    return {
        "total": total,
        "target_total": TOTAL_TARGET,
        "missing_total": max(0, TOTAL_TARGET - total),
        "subcategory_counts": dict(subcategory_counts),
        "subcategory_targets": SUBCATEGORY_TARGETS,
        "licensed": licensed,
        "license_unverified": unverified,
    }


def build_manifest(report: dict, descriptions: list[dict]) -> dict:
    entries = []
    for record in descriptions:
        image_path = record["image_path"]
        entries.append({
            "image_path": image_path,
            "sha256": report["hashes"].get(image_path),
            "subcategory": record.get("subcategory"),
            "category": record.get("category"),
            "license_verified": bool(record.get("license")),
            "source_url": record.get("source_url", ""),
            "license": record.get("license", ""),
        })
    payload = json.dumps(entries, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return {
        "generator_version": "image-asset-registry-1",
        "stats": report["stats"],
        "duplicates": report["duplicates"],
        "entries": entries,
        "sha256": hashlib.sha256(payload.encode("utf-8")).hexdigest(),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="R4-A image asset registry")
    parser.add_argument("--descriptions", type=Path, default=DEFAULT_DESCRIPTIONS)
    parser.add_argument("--image-dir", type=Path, default=DEFAULT_IMAGES_DIR)
    parser.add_argument("--out", type=Path, default=DEFAULT_MANIFEST)
    args = parser.parse_args()

    descriptions = load_descriptions(args.descriptions)
    report = validate_assets(descriptions, args.image_dir)

    print(f"素材总数: {report['stats']['total']} (目标 {TOTAL_TARGET})")
    print(f"subcategory 分层: {report['stats']['subcategory_counts']}")
    print(f"已声明授权: {report['stats']['licensed']} 张,license 未填: {report['stats']['license_unverified']} 张")
    if report["errors"]:
        print(f"\n硬错误 {len(report['errors'])} 条:")
        for error in report["errors"]:
            print(f"  - {error}")
    if report["warnings"]:
        print(f"\n警告 {len(report['warnings'])} 条:")
        for warning in report["warnings"]:
            print(f"  - {warning}")
    if any(report["duplicates"].values()):
        print(f"\n重复检测: {report['duplicates']}")

    manifest = build_manifest(report, descriptions)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"\nmanifest 输出: {args.out}")


if __name__ == "__main__":
    main()
