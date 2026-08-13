import sys
import unittest
from collections import Counter
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

import build_conversation_dataset  # noqa: E402
from evaluation_contract import validate_dataset_split, validate_split_isolation  # noqa: E402


class ConversationDatasetTest(unittest.TestCase):

    def test_builds_twenty_balanced_traceable_isolated_conversations(self):
        faq_path = ROOT / "bootstrap/data/faq/industrial_faq.jsonl"
        faq = build_conversation_dataset.load_jsonl(faq_path)
        cases = build_conversation_dataset.build_cases(faq, faq_path)
        repeated = build_conversation_dataset.build_cases(faq, faq_path)

        self.assertEqual(20, len(cases))
        self.assertEqual(build_conversation_dataset.render(cases),
                         build_conversation_dataset.render(repeated))
        self.assertEqual({"tuning": 10, "frozen": 10}, Counter(c["split"] for c in cases))
        self.assertEqual({"ellipsis": 10, "cross_turn_reference": 10},
                         Counter(c["conversation_type"] for c in cases))
        self.assertEqual({2: 10, 3: 10}, Counter(len(c["turns"]) for c in cases))
        source_by_question = {item["question"]: item for item in faq}
        for case in cases:
            source = source_by_question[case["canonical_target_query"]]
            self.assertEqual(len(case["turns"]) - 1, case["target_turn_index"])
            self.assertTrue(all(turn["role"] == "user" for turn in case["turns"]))
            self.assertEqual(source["answer"], case["golden_answer"])
            self.assertEqual([source["source_doc"]], case["golden_source_ids"])
            self.assertEqual(case["canonical_target_query"], case["provenance"]["source_record_id"])
        tuning = [case for case in cases if case["split"] == "tuning"]
        frozen = [case for case in cases if case["split"] == "frozen"]
        validate_dataset_split(tuning, "tuning", kind="conversation")
        validate_dataset_split(frozen, "frozen", kind="conversation")
        validate_split_isolation(tuning, frozen, kind="conversation")


if __name__ == "__main__":
    unittest.main()
