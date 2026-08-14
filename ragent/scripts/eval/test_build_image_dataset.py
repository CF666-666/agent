#!/usr/bin/env python3
"""Offline regression tests for the R4-B industrial image dataset generator."""

import json
import random
import sys
import unittest
from collections import Counter
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

import build_image_dataset  # noqa: E402
import evaluation_contract  # noqa: E402


def make_asset(index: int, subcategory: str, **overrides) -> dict:
    subject = {
        "engineering_drawing": ["连铸机", "桥式起重机", "精馏塔", "轧机"],
        "scanned_manual": ["离心泵手册", "压缩机铭牌", "换热器手册", "过滤器铭牌"],
        "site_photo": ["高炉现场", "变电站现场", "轧线现场", "料场现场"],
    }[subcategory][index % 4]
    record = {
        "image_path": f"drawings/{subcategory}_{index}.jpg",
        "description": f"这是{subject}的工业图像语义描述，包含设备布局与部件信息。",
        "category": "industrial_equipment",
        "subcategory": subcategory,
        "image_subject": subject,
        "source_url": f"https://example.com/{index}",
        "license": "Unsplash License",
        "generated_by": "qwen-vl-max",
    }
    record.update(overrides)
    return record


def make_40_assets() -> list[dict]:
    assets = []
    index = 0
    for subcategory, count in [("engineering_drawing", 20),
                               ("scanned_manual", 10),
                               ("site_photo", 10)]:
        for i in range(count):
            assets.append(make_asset(index, subcategory))
            index += 1
    return assets


class BuildImageDatasetTest(unittest.TestCase):

    def setUp(self):
        self.assets = make_40_assets()
        self.cases = build_image_dataset.build_cases(
            self.assets, Path("descriptions.jsonl"), random.Random(20260814))

    def test_total_100_and_split_50_50(self):
        self.assertEqual(100, len(self.cases))
        self.assertEqual(
            {"tuning": 50, "frozen": 50},
            dict(Counter(case["split"] for case in self.cases)),
        )

    def test_capabilities_each_25(self):
        counts = Counter(case["business_tags"][1] for case in self.cases)
        self.assertEqual(
            {"device_identification": 25, "component_location": 25,
             "parameter_reading": 25, "fault_symptom": 25},
            dict(counts),
        )

    def test_each_image_2_to_3_questions(self):
        counts = Counter(case["golden_image_paths"][0] for case in self.cases)
        for image_path, count in counts.items():
            self.assertIn(count, (2, 3), f"{image_path} has {count} questions")

    def test_same_image_not_split_across_splits(self):
        image_split = {}
        for case in self.cases:
            image_path = case["golden_image_paths"][0]
            if image_path in image_split:
                self.assertEqual(image_split[image_path], case["split"])
            image_split[image_path] = case["split"]

    def test_queries_do_not_leak_description(self):
        for case in self.cases:
            # query 不应包含 description 的关键短语"工业图像语义描述"
            self.assertNotIn("工业图像语义描述", case["query"])

    def test_device_identification_has_no_subject(self):
        for case in self.cases:
            if case["business_tags"][1] == "device_identification":
                # 设备识别类问题不贴 image_subject(答案即设备类型)
                subject = case["golden_answer"][3:7]  # description 里 subject 位置
                self.assertNotIn(subject, case["query"])

    def test_passes_contract_validation(self):
        tuning = [c for c in self.cases if c["split"] == "tuning"]
        frozen = [c for c in self.cases if c["split"] == "frozen"]
        evaluation_contract.validate_records(self.cases)
        evaluation_contract.validate_split_isolation(tuning, frozen)

    def test_filters_unlicensed_assets(self):
        assets = make_40_assets()
        assets[0]["license"] = ""
        assets[0]["source_url"] = ""
        eligible = build_image_dataset.select_eligible(assets)
        # license 未填的素材被过滤
        self.assertEqual(39, len(eligible))
        # 过滤后不足 40 张,不满足 20/10/10 分布,应报错
        with self.assertRaises(ValueError):
            build_image_dataset.build_cases(
                eligible, Path("descriptions.jsonl"), random.Random(1))

    def test_license_without_source_url_is_eligible(self):
        # 授权由提供者声明:license 非空即可,source_url 不再是硬要求
        assets = make_40_assets()
        assets[0]["license"] = "proprietary"
        assets[0]["source_url"] = ""
        eligible = build_image_dataset.select_eligible(assets)
        self.assertEqual(40, len(eligible))

    def test_missing_subcategory_raises(self):
        assets = make_40_assets()
        assets[0].pop("subcategory")
        with self.assertRaises(ValueError):
            build_image_dataset.select_eligible(assets)

    def test_finalize_approve_and_reject(self):
        review = {}
        for case in self.cases:
            if case["id"].endswith("001"):
                review[case["id"]] = {"action": "reject", "note": "与图片不符"}
            else:
                review[case["id"]] = {"action": "approve", "note": ""}
        finalized, rejected = build_image_dataset.finalize(self.cases, review)
        # 被 reject 的 001 条目移除,其余 human_review=False
        self.assertEqual(2, rejected)
        self.assertEqual(98, len(finalized))
        self.assertTrue(all(c["human_review"] is False for c in finalized))

    def test_split_subcategory_balanced(self):
        # 每个 split 内 subcategory 图层面分布应为 10/5/5
        for split in ("tuning", "frozen"):
            image_sub_counts = {}
            seen = set()
            for case in self.cases:
                if case["split"] != split:
                    continue
                image_path = case["golden_image_paths"][0]
                if image_path not in seen:
                    seen.add(image_path)
                    image_sub_counts[case["business_tags"][0]] = \
                        image_sub_counts.get(case["business_tags"][0], 0) + 1
            self.assertEqual(
                {"engineering_drawing": 10, "scanned_manual": 5, "site_photo": 5},
                image_sub_counts)


if __name__ == "__main__":
    unittest.main()
