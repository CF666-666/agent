#!/usr/bin/env python3
"""Offline regression tests for reproducible retrieval evaluation artifacts."""

import random
import subprocess
import sys
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

import build_dataset_v2  # noqa: E402
import merge_eval_reports  # noqa: E402
import retrieval_eval  # noqa: E402


class EvaluationArtifactsTest(unittest.TestCase):

    def test_dataset_generation_is_deterministic_and_source_grounded(self):
        faq = build_dataset_v2.load_jsonl(ROOT / "bootstrap/data/faq/industrial_faq.jsonl")
        images = build_dataset_v2.load_jsonl(ROOT / "bootstrap/data/images/descriptions.jsonl")
        edges = build_dataset_v2.load_jsonl(ROOT / "bootstrap/data/hypergraph/hyperedges.jsonl")

        def build(seed: int):
            rng = random.Random(seed)
            cases = build_dataset_v2.build_faq_cases(faq, build_dataset_v2.DEFAULT_FAQ, rng)
            cases.extend(build_dataset_v2.build_image_cases(images, build_dataset_v2.DEFAULT_IMAGES, rng))
            cases.extend(build_dataset_v2.build_relation_cases(edges, build_dataset_v2.DEFAULT_HYPEREDGES, rng))
            build_dataset_v2.validate(cases, faq, images, edges)
            return cases

        first = build(202603)
        self.assertEqual(first, build(202603))
        self.assertEqual(
            {scene: sum(item["scene"] == scene for item in first)
             for scene in ("fact", "colloquial", "image", "relation")},
            {"fact": 25, "colloquial": 25, "image": 25, "relation": 25},
        )
        self.assertTrue(all(item["golden_source_ids"] for item in first))

    def test_checked_in_dataset_matches_generator_output(self):
        result = subprocess.run(
            [sys.executable, "-B", str(SCRIPT_DIR / "build_dataset_v2.py"), "--check"],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )

        self.assertEqual(0, result.returncode, msg=result.stderr)
        self.assertIn("match deterministic generation", result.stdout)

    def test_report_metadata_records_dataset_hash_and_all_switches(self):
        dataset = ROOT / "scripts/eval/datasets/industrial_eval_v2.jsonl"

        descriptor = retrieval_eval.describe_dataset(dataset)
        options = retrieval_eval.retrieval_options(False, False, True, False, "C-hypergraph")
        runtime = retrieval_eval.runtime_metadata("local-isolated", 12)

        self.assertEqual(64, len(descriptor["sha256"]))
        self.assertEqual("scripts/eval/datasets/industrial_eval_v2.jsonl", descriptor["path"])
        self.assertEqual(
            {"label": "C-hypergraph", "enableRewrite": False, "enableImage": False,
             "enableHyperGraph": True, "enableFusion": False, "retrievalOnly": False},
            options,
        )
        self.assertEqual({"label": "local-isolated", "request_timeout_seconds": 12}, runtime)

    def test_evaluation_slice_applies_offset_after_scene_filtering(self):
        items = [
            {"scene": "fact", "query": "fact-1"},
            {"scene": "relation", "query": "relation-1"},
            {"scene": "relation", "query": "relation-2"},
            {"scene": "relation", "query": "relation-3"},
        ]

        selected = retrieval_eval.select_evaluation_items(items, "relation", offset=1, limit=1)

        self.assertEqual(["relation-2"], [item["query"] for item in selected])
        with self.assertRaisesRegex(ValueError, "offset"):
            retrieval_eval.select_evaluation_items(items, "relation", offset=-1, limit=0)
        with self.assertRaisesRegex(ValueError, "empty"):
            retrieval_eval.select_evaluation_items(items, "relation", offset=3, limit=1)

    def test_evaluation_case_id_distinguishes_labeled_cases_with_same_query(self):
        base = {"scene": "image", "query": "same query", "golden_answer": "answer"}

        self.assertNotEqual(
            retrieval_eval.evaluation_case_id({**base, "golden_source_ids": ["image-a"]}),
            retrieval_eval.evaluation_case_id({**base, "golden_source_ids": ["image-b"]}),
        )

    def test_report_merger_rejects_mismatched_retrieval_options(self):
        base = {
            "schema_version": 2,
            "mode": "rewrite-off",
            "dataset": {"path": "dataset.jsonl", "sha256": "a" * 64},
            "summary": {},
            "results": [],
        }
        text_only = {**base, "retrieval_options": {"label": "A", "enableRewrite": False,
                                                     "enableImage": False, "enableHyperGraph": False,
                                                     "enableFusion": False}}
        full_chain = {**base, "retrieval_options": {"label": "D", "enableRewrite": False,
                                                      "enableImage": True, "enableHyperGraph": True,
                                                      "enableFusion": True}}

        with self.assertRaisesRegex(ValueError, "different retrieval options"):
            merge_eval_reports.merge_documents([text_only, full_chain],
                                               [Path("A.json"), Path("D.json")])

    def test_report_merger_rejects_mismatched_runtime(self):
        base = {
            "schema_version": 2,
            "mode": "rewrite-off",
            "dataset": {"path": "dataset.jsonl", "sha256": "a" * 64},
            "retrieval_options": {"label": "C", "enableRewrite": False,
                                  "enableImage": False, "enableHyperGraph": True,
                                  "enableFusion": True},
            "summary": {},
            "results": [],
        }

        with self.assertRaisesRegex(ValueError, "different runtimes"):
            merge_eval_reports.merge_documents(
                [{**base, "runtime": {"label": "rate-limit-on", "request_timeout_seconds": 12}},
                 {**base, "runtime": {"label": "rate-limit-off", "request_timeout_seconds": 12}}],
                [Path("one.json"), Path("two.json")],
            )

    def test_report_merger_retains_shared_provenance(self):
        document = {
            "schema_version": 2,
            "mode": "rewrite-off",
            "dataset": {"path": "dataset.jsonl", "sha256": "a" * 64},
            "retrieval_options": {"label": "D", "enableRewrite": False,
                                  "enableImage": True, "enableHyperGraph": True, "enableFusion": True},
            "summary": {},
            "results": [{"scene": "image", "ok": True, "hit": {"1": True, "3": True, "5": True},
                         "mrr": 1.0, "channel_hit": True, "source_id_hit": True}],
        }

        merged = merge_eval_reports.merge_documents([document], [Path("D-image.json")])

        self.assertEqual(document["dataset"], merged["dataset"])
        self.assertEqual(document["retrieval_options"], merged["retrieval_options"])
        self.assertEqual(1, merged["summary"]["total"])

    def test_report_merger_rejects_duplicate_queries(self):
        document = {
            "schema_version": 2,
            "mode": "rewrite-off",
            "dataset": {"path": "dataset.jsonl", "sha256": "a" * 64},
            "retrieval_options": {"label": "C", "enableRewrite": False,
                                  "enableImage": False, "enableHyperGraph": True,
                                  "enableFusion": True},
            "summary": {},
            "results": [{"case_id": "same-case", "query": "same query"}],
        }

        with self.assertRaisesRegex(ValueError, "duplicate evaluation cases"):
            merge_eval_reports.merge_documents([document, document], [Path("one.json"), Path("two.json")])


if __name__ == "__main__":
    unittest.main()
