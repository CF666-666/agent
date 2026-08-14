#!/usr/bin/env python3
"""Offline regression tests for the R4-A image asset registry."""

import json
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

import image_asset_registry  # noqa: E402


def make_assets(tmp: Path, count: int = 3):
    image_dir = tmp / "images"
    drawings = image_dir / "drawings"
    drawings.mkdir(parents=True)
    descriptions = []
    for i in range(count):
        name = f"img_{i}.jpg"
        (drawings / name).write_bytes(f"content-{i}".encode("utf-8"))
        descriptions.append({
            "image_path": f"drawings/{name}",
            "description": f"desc {i}",
            "category": "steel_metallurgy",
            "subcategory": "engineering_drawing",
            "source_url": f"https://example.com/{i}",
            "license": "Unsplash License",
            "generated_by": "qwen-vl-max",
        })
    return image_dir, descriptions


class ValidateAssetsTest(unittest.TestCase):

    def test_valid_assets_produce_hashes_and_licensed_stats(self):
        with tempfile.TemporaryDirectory() as tmp:
            image_dir, descriptions = make_assets(Path(tmp), count=3)
            report = image_asset_registry.validate_assets(descriptions, image_dir)
            self.assertEqual([], report["errors"])
            self.assertEqual(3, len(report["hashes"]))
            self.assertEqual(3, report["stats"]["licensed"])
            self.assertEqual(0, report["stats"]["license_unverified"])

    def test_missing_required_field_is_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            image_dir, descriptions = make_assets(Path(tmp), count=1)
            descriptions[0].pop("category")
            report = image_asset_registry.validate_assets(descriptions, image_dir)
            self.assertTrue(any("category" in e for e in report["errors"]))

    def test_missing_license_is_warning_not_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            image_dir, descriptions = make_assets(Path(tmp), count=1)
            descriptions[0]["license"] = ""
            descriptions[0]["source_url"] = ""
            report = image_asset_registry.validate_assets(descriptions, image_dir)
            self.assertEqual([], report["errors"])
            self.assertTrue(any("license" in w for w in report["warnings"]))
            self.assertEqual(1, report["stats"]["license_unverified"])

    def test_license_without_source_url_counts_as_licensed(self):
        # 授权由提供者声明:license 非空即可,source_url 不再是硬要求
        with tempfile.TemporaryDirectory() as tmp:
            image_dir, descriptions = make_assets(Path(tmp), count=1)
            descriptions[0]["license"] = "proprietary"
            descriptions[0]["source_url"] = ""
            report = image_asset_registry.validate_assets(descriptions, image_dir)
            self.assertEqual([], report["errors"])
            self.assertEqual(1, report["stats"]["licensed"])
            self.assertEqual(0, report["stats"]["license_unverified"])
            manifest = image_asset_registry.build_manifest(report, descriptions)
            self.assertTrue(manifest["entries"][0]["license_verified"])

    def test_content_duplicate_detected_by_sha256(self):
        with tempfile.TemporaryDirectory() as tmp:
            image_dir, descriptions = make_assets(Path(tmp), count=2)
            # 第二个文件内容与第一个相同(不同文件名)
            (image_dir / "drawings" / "img_1.jpg").write_bytes(b"content-0")
            report = image_asset_registry.validate_assets(descriptions, image_dir)
            self.assertTrue(report["duplicates"]["content_duplicate"])

    def test_path_duplicate_detected(self):
        with tempfile.TemporaryDirectory() as tmp:
            image_dir, descriptions = make_assets(Path(tmp), count=2)
            descriptions[1]["image_path"] = descriptions[0]["image_path"]
            report = image_asset_registry.validate_assets(descriptions, image_dir)
            self.assertTrue(report["duplicates"]["path_duplicate"])

    def test_missing_file_is_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            image_dir, descriptions = make_assets(Path(tmp), count=1)
            (image_dir / "drawings" / "img_0.jpg").unlink()
            report = image_asset_registry.validate_assets(descriptions, image_dir)
            self.assertTrue(any("missing" in e for e in report["errors"]))

    def test_manifest_has_overall_sha256(self):
        with tempfile.TemporaryDirectory() as tmp:
            image_dir, descriptions = make_assets(Path(tmp), count=3)
            report = image_asset_registry.validate_assets(descriptions, image_dir)
            manifest = image_asset_registry.build_manifest(report, descriptions)
            self.assertEqual(64, len(manifest["sha256"]))
            self.assertEqual(3, len(manifest["entries"]))


if __name__ == "__main__":
    unittest.main()
