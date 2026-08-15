#!/usr/bin/env python3
"""Offline regression tests for the R5 main dataset merger."""

import sys
import unittest
from collections import Counter
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

import build_main_dataset  # noqa: E402


def mock_image(count: int = 100, start: int = 0,
               questions_per_image: int = 3) -> list[dict]:
    """模拟 R4-B 真实设计:每张图 2~3 条问题(不同能力维度),共享同一 image_path
    与 provenance.source_record_id。count 是问题数,图片数 = ceil(count/每图问题数)。"""
    records = []
    image_index = 0
    question_on_image = 0
    for i in range(count):
        if question_on_image >= questions_per_image:
            image_index += 1
            question_on_image = 0
        path = f"drawings/mock_{start + image_index + 1}.jpg"
        records.append({
            "schema_version": 1,
            "case_type": "single_turn",
            "id": f"r5-image-frozen-{start + i + 1:03d}",
            "query": f"mock image query {start + i + 1}",
            "golden_answer": f"mock answer {start + i + 1}",
            "scene": "image",
            "split": "frozen",
            "ragas_group": "image",
            "expected_channels": ["IMAGE_SEMANTIC"],
            "golden_source_ids": [path],
            "golden_image_paths": [path],
            "business_tags": ["industrial_equipment"],
            "provenance": {
                "source_file": "bootstrap/data/images/descriptions.jsonl",
                "source_record_id": path,
            },
        })
        question_on_image += 1
    return records


def mock_fact(count: int = 50) -> list[dict]:
    records = []
    for i in range(count):
        records.append({
            "schema_version": 1,
            "case_type": "single_turn",
            "id": f"r5-fact-tuning-{i + 1:03d}",
            "query": f"mock fact question {i + 1}",
            "golden_answer": f"mock fact answer {i + 1}",
            "scene": "fact",
            "split": "tuning",
            "ragas_group": "text",
            "expected_channels": ["VECTOR_GLOBAL", "INTENT_DIRECTED"],
            "golden_source_ids": ["steel_metallurgy"],
            "business_tags": ["故障诊断"],
            "provenance": {
                "source_file": "bootstrap/data/faq/industrial_faq.jsonl",
                "source_record_id": f"mock fact question {i + 1}",
            },
        })
    return records


class SceneOfTest(unittest.TestCase):

    def test_noise_by_ragas_group(self):
        self.assertEqual("noise", build_main_dataset.scene_of(
            {"scene": "colloquial", "ragas_group": "noise"}))

    def test_standard_scenes(self):
        self.assertEqual("fact", build_main_dataset.scene_of({"scene": "fact"}))
        self.assertEqual("image", build_main_dataset.scene_of({"scene": "image"}))
        self.assertEqual("relation", build_main_dataset.scene_of({"scene": "relation"}))

    def test_rejects_non_noise_colloquial(self):
        with self.assertRaises(ValueError):
            build_main_dataset.scene_of({"id": "x", "scene": "colloquial"})


class MergeTest(unittest.TestCase):

    def test_scene_counts_and_split_retained(self):
        subsets = {
            "fact": mock_fact(50),
            "noise": mock_noise(40),
            "image": mock_image(100),
            "relation": mock_relation(50),
        }
        build_main_dataset.validate_subsets(subsets)
        merged = build_main_dataset.merge_records(subsets)
        build_main_dataset.validate_merged(merged)

        self.assertEqual(240, len(merged))
        self.assertEqual(
            {"fact": 50, "noise": 40, "image": 100, "relation": 50},
            dict(Counter(build_main_dataset.scene_of(r) for r in merged)),
        )
        # split 字段原样保留且值合法
        splits = {r.get("split") for r in merged}
        self.assertEqual({"tuning", "frozen"}, splits)

    def test_rejects_duplicate_id(self):
        fact = mock_fact(50)
        fact[1]["id"] = fact[0]["id"]
        subsets = {
            "fact": fact,
            "noise": mock_noise(40),
            "image": mock_image(100),
            "relation": mock_relation(50),
        }
        with self.assertRaises(Exception):
            build_main_dataset.validate_merged(
                build_main_dataset.merge_records(subsets))

    def test_image_multi_question_per_image_does_not_trigger_dedup(self):
        # R4-B 每图 2~3 问:同一图片的多条问题(不同能力)共享 image/provenance 键,
        # 不应被「子集内证据键不重复」误判为重复。
        subsets = {
            "fact": mock_fact(50),
            "noise": mock_noise(40),
            "image": mock_image(100, questions_per_image=3),  # 100 问 ≈ 34 张图
            "relation": mock_relation(50),
        }
        build_main_dataset.validate_subsets(subsets)

    def test_rejects_duplicate_hyperedge_key_in_relation(self):
        # relation 子集内同一超边重复(非每边多问设计)应仍被拦截
        relation = mock_relation(50)
        relation[1]["golden_hyperedge_ids"] = relation[0]["golden_hyperedge_ids"]
        relation[1]["golden_source_ids"] = relation[0]["golden_source_ids"]
        relation[1]["provenance"] = relation[0]["provenance"]
        subsets = {
            "fact": mock_fact(50),
            "noise": mock_noise(40),
            "image": mock_image(100, questions_per_image=3),
            "relation": relation,
        }
        with self.assertRaises(ValueError):
            build_main_dataset.validate_subsets(subsets)

    def test_rejects_canonical_question_overlap(self):
        noise = mock_noise(40)
        # 让 fact 的 query 与 noise 的 canonical_query 重叠
        fact = mock_fact(50)
        fact[0]["query"] = noise[0]["canonical_query"]
        subsets = {
            "fact": fact,
            "noise": noise,
            "image": mock_image(100),
            "relation": mock_relation(50),
        }
        with self.assertRaises(ValueError):
            build_main_dataset.validate_subsets(subsets)

    def test_rendering_is_deterministic(self):
        subsets = {
            "fact": mock_fact(50),
            "noise": mock_noise(40),
            "image": mock_image(100),
            "relation": mock_relation(50),
        }
        merged = build_main_dataset.merge_records(subsets)
        self.assertEqual(build_main_dataset.render(merged), build_main_dataset.render(merged))


def mock_noise(count: int = 40) -> list[dict]:
    records = []
    for i in range(count):
        records.append({
            "schema_version": 1,
            "case_type": "single_turn",
            "id": f"r3-noise-tuning-{i + 1:03d}",
            "query": f"mock noise query {i + 1}",
            "canonical_query": f"mock canonical {i + 1}",
            "mutation_notes": "mock",
            "noise_type": "typo_homophone",
            "golden_answer": f"mock noise answer {i + 1}",
            "scene": "colloquial",
            "split": "tuning",
            "ragas_group": "noise",
            "expected_channels": ["VECTOR_GLOBAL", "INTENT_DIRECTED"],
            "golden_source_ids": ["steel_metallurgy"],
            "business_tags": ["故障诊断", "typo_homophone"],
            "provenance": {
                "source_file": "bootstrap/data/faq/industrial_faq.jsonl",
                "source_record_id": f"mock canonical {i + 1}",
            },
        })
    return records


def mock_relation(count: int = 50) -> list[dict]:
    records = []
    for i in range(count):
        edge_id = f"edge-{i + 1}"
        records.append({
            "schema_version": 1,
            "case_type": "single_turn",
            "id": f"r2-relation-tuning-{i + 1:03d}",
            "query": f"mock relation query {i + 1}",
            "golden_answer": "mock relation answer",
            "scene": "relation",
            "split": "tuning",
            "ragas_group": "relation",
            "expected_channels": ["HYPERGRAPH"],
            "golden_source_ids": [edge_id],
            "golden_hyperedge_ids": [edge_id],
            "golden_source_documents": ["steel_metallurgy"],
            "golden_hyperedge_sources": {edge_id: "steel_metallurgy"},
            "business_tags": ["single_edge"],
            "provenance": {
                "source_file": "bootstrap/data/hypergraph/hyperedges.jsonl",
                "source_record_id": edge_id,
            },
        })
    return records


if __name__ == "__main__":
    unittest.main()
