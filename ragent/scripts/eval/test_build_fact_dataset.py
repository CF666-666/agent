#!/usr/bin/env python3
"""Offline regression tests for the R5 fact dataset generator."""

import random
import sys
import unittest
from collections import Counter
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

import build_fact_dataset  # noqa: E402
import evaluation_contract  # noqa: E402


class BuildFactDatasetTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.faq = build_fact_dataset.load_jsonl(
            ROOT / "bootstrap/data/faq/industrial_faq.jsonl")
        cls.noise = build_fact_dataset.DEFAULT_NOISE
        cls.cases = build_fact_dataset.build_cases(
            cls.faq, cls.noise, random.Random(20260814))

    def test_count_and_split(self):
        self.assertEqual(50, len(self.cases))
        self.assertEqual(
            {"tuning": 25, "frozen": 25},
            dict(Counter(case["split"] for case in self.cases)),
        )

    def test_questions_are_unique(self):
        questions = [case["query"] for case in self.cases]
        self.assertEqual(len(questions), len(set(questions)))

    def test_source_doc_split_isolation(self):
        for case in self.cases:
            doc = case["golden_source_ids"][0]
            if case["split"] == "tuning":
                self.assertIn(doc, ("steel_metallurgy", "petrochemical"))
            else:
                self.assertEqual("power_energy", doc)

    def test_no_overlap_with_noise_questions(self):
        noise_questions = build_fact_dataset.load_excluded_questions(self.noise)
        fact_questions = {case["query"] for case in self.cases}
        self.assertFalse(fact_questions & noise_questions)

    def test_split_isolation_passes_contract(self):
        tuning = [case for case in self.cases if case["split"] == "tuning"]
        frozen = [case for case in self.cases if case["split"] == "frozen"]
        evaluation_contract.validate_split_isolation(tuning, frozen)

    def test_category_distribution_balanced(self):
        for split in ("tuning", "frozen"):
            cats = Counter(case["business_tags"][0] for case in self.cases if case["split"] == split)
            counts = sorted(cats.values())
            self.assertLessEqual(max(counts) - min(counts), 2)

    def test_schema_fields_present(self):
        case = self.cases[0]
        self.assertEqual(1, case["schema_version"])
        self.assertEqual("single_turn", case["case_type"])
        self.assertEqual("fact", case["scene"])
        self.assertEqual("text", case["ragas_group"])
        self.assertEqual(["VECTOR_GLOBAL", "INTENT_DIRECTED"], case["expected_channels"])
        self.assertIn(case["golden_source_ids"][0], ("steel_metallurgy", "petrochemical", "power_energy"))


if __name__ == "__main__":
    unittest.main()
