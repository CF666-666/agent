import random
import sys
import unittest
from collections import Counter
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

import build_noise_dataset  # noqa: E402
from evaluation_contract import validate_dataset_split, validate_split_isolation  # noqa: E402


class NoiseDatasetTest(unittest.TestCase):

    def setUp(self):
        self.faq_path = ROOT / "bootstrap/data/faq/industrial_faq.jsonl"
        self.faq = build_noise_dataset.load_jsonl(self.faq_path)

    def test_builds_deterministic_balanced_and_isolated_cases(self):
        first = build_noise_dataset.build_cases(self.faq, self.faq_path)
        second = build_noise_dataset.build_cases(self.faq, self.faq_path)

        self.assertEqual(first, second)
        self.assertEqual(40, len(first))
        self.assertEqual({"tuning": 20, "frozen": 20}, Counter(c["split"] for c in first))
        self.assertEqual(
            {noise_type: 10 for noise_type in build_noise_dataset.NOISE_TYPES},
            Counter(c["noise_type"] for c in first),
        )
        for split in ("tuning", "frozen"):
            split_cases = [case for case in first if case["split"] == split]
            self.assertEqual(
                {noise_type: 5 for noise_type in build_noise_dataset.NOISE_TYPES},
                Counter(case["noise_type"] for case in split_cases),
            )
            validate_dataset_split(split_cases, split)
        validate_split_isolation(
            [case for case in first if case["split"] == "tuning"],
            [case for case in first if case["split"] == "frozen"],
        )

    def test_every_variant_is_traceable_and_not_a_prefix_template(self):
        cases = build_noise_dataset.build_cases(self.faq, self.faq_path)
        source_by_question = {item["question"]: item for item in self.faq}

        for case in cases:
            canonical = case["canonical_query"]
            source = source_by_question[canonical]
            self.assertNotEqual(canonical, case["query"])
            self.assertEqual(canonical, case["provenance"]["source_record_id"])
            self.assertEqual(source["answer"], case["golden_answer"])
            self.assertEqual([source["source_doc"]], case["golden_source_ids"])
            self.assertTrue(case["mutation_notes"].strip())
            self.assertFalse(case["query"].endswith(canonical))
            self.assertEqual("noise", case["ragas_group"])
            self.assertIn(case["noise_type"], case["business_tags"])

    def test_rejects_missing_source_or_duplicate_source_record(self):
        specs = list(build_noise_dataset.CASE_SPECS)
        missing = dict(specs[0], canonical_query="不存在的问题")
        with self.assertRaisesRegex(ValueError, "source question not found"):
            build_noise_dataset.build_cases(self.faq, self.faq_path, [missing, *specs[1:]])

        duplicate = dict(specs[1], canonical_query=specs[0]["canonical_query"])
        with self.assertRaisesRegex(ValueError, "duplicate source question"):
            build_noise_dataset.build_cases(self.faq, self.faq_path, [specs[0], duplicate, *specs[2:]])


if __name__ == "__main__":
    unittest.main()
