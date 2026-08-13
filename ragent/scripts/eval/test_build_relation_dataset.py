import random
import sys
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

import build_relation_dataset  # noqa: E402
from evaluation_contract import validate_dataset_split, validate_split_isolation  # noqa: E402


class RelationDatasetTest(unittest.TestCase):

    def test_builds_isolated_balanced_tuning_and_frozen_sets(self):
        edges = build_relation_dataset.load_jsonl(
            ROOT / "bootstrap/data/hypergraph/hyperedges.jsonl")

        first = build_relation_dataset.build_cases(edges, random.Random(20260813))
        second = build_relation_dataset.build_cases(edges, random.Random(20260813))

        self.assertEqual(first, second)
        self.assertEqual(50, len(first))
        self.assertEqual(25, sum(case["split"] == "tuning" for case in first))
        self.assertEqual(25, sum(case["split"] == "frozen" for case in first))
        self.assertEqual(10, sum("two_hop" in case["business_tags"] for case in first))
        self.assertGreaterEqual(sum("alias_variant" in case["business_tags"] for case in first), 2)
        self.assertGreaterEqual(sum("similar_entity_distractor" in case["business_tags"] for case in first), 4)
        tuning = [case for case in first if case["split"] == "tuning"]
        frozen = [case for case in first if case["split"] == "frozen"]
        validate_dataset_split(tuning, "tuning")
        validate_dataset_split(frozen, "frozen")
        validate_split_isolation(tuning, frozen)
        self.assertTrue(all(case["golden_source_documents"] for case in first))


if __name__ == "__main__":
    unittest.main()
