import sys
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from classify_relation_failures import classify_result  # noqa: E402


class RelationFailureClassifierTest(unittest.TestCase):

    def test_alias_timeout_remains_unattributed_execution_failure(self):
        result = {"ok": False, "retrieval_status": "channel_timed_out",
                  "business_tags": ["alias_variant"]}
        self.assertEqual("execution_failed_unattributed", classify_result(result))

    def test_alias_miss_remains_recall_failure(self):
        result = {"ok": True, "business_tags": ["alias_variant"], "relation": {
            "channel": {"hyperedge_hit": {"1": False, "5": False},
                        "hyperedge_recall": {"5": 0.0},
                        "source_accuracy": False, "path_hit": False},
            "final_reference": {"hit": {"1": False, "5": False}},
        }}
        self.assertEqual("hyperedge_not_recalled", classify_result(result))

    def test_non_alias_timeout_is_entity_extraction_failure(self):
        result = {"ok": False, "retrieval_status": "channel_timed_out",
                  "business_tags": ["single_edge"]}
        self.assertEqual("execution_failed_unattributed", classify_result(result))

    def test_channel_hit_below_first_is_ranking_failure(self):
        result = {"ok": True, "relation": {
            "channel": {"hyperedge_hit": {"1": False, "5": True},
                        "hyperedge_recall": {"5": 1.0},
                        "source_accuracy": True, "path_hit": True},
            "final_reference": {"hit": {"1": False, "5": True}},
        }}
        self.assertEqual("ranking_error", classify_result(result))

    def test_channel_miss_is_hyperedge_recall_failure(self):
        result = {"ok": True, "relation": {
            "channel": {"hyperedge_hit": {"1": False, "5": False},
                        "hyperedge_recall": {"5": 0.0},
                        "source_accuracy": False, "path_hit": False},
            "final_reference": {"hit": {"1": False, "5": False}},
        }}
        self.assertEqual("hyperedge_not_recalled", classify_result(result))

    def test_source_mismatch_precedes_path_or_ranking(self):
        result = {"ok": True, "relation": {
            "channel": {"hyperedge_hit": {"1": True, "5": True},
                        "hyperedge_recall": {"5": 1.0},
                        "source_accuracy": False, "path_hit": False},
            "final_reference": {"hit": {"1": True, "5": True}},
        }}
        self.assertEqual("source_id_mismatch", classify_result(result))

    def test_final_rank_loss_is_fusion_crowd_out(self):
        result = {"ok": True, "relation": {
            "channel": {"hyperedge_hit": {"1": True, "5": True},
                        "hyperedge_recall": {"5": 1.0},
                        "source_accuracy": True, "path_hit": True},
            "final_reference": {"hit": {"1": False, "5": True}},
        }}
        self.assertEqual("fusion_crowd_out", classify_result(result, fusion_enabled=True))

    def test_final_rank_loss_is_not_attributed_to_fusion_when_disabled(self):
        result = {"ok": True, "relation": {
            "channel": {"hyperedge_hit": {"1": True, "5": True},
                        "hyperedge_recall": {"5": 1.0},
                        "source_accuracy": True, "path_hit": True},
            "final_reference": {"hit": {"1": False, "5": True}},
        }}
        self.assertEqual("passed", classify_result(result, fusion_enabled=False))

    def test_incomplete_two_hop_path_is_path_ranking_failure(self):
        result = {"ok": True, "relation": {
            "channel": {"hyperedge_hit": {"1": True, "5": True},
                        "hyperedge_recall": {"5": 1.0},
                        "source_accuracy": True, "path_hit": False},
            "final_reference": {"hit": {"1": True, "5": True}},
        }}
        self.assertEqual("ranking_error", classify_result(result))

    def test_partial_two_edge_recall_is_recall_failure_not_source_failure(self):
        result = {"ok": True, "relation": {
            "channel": {"hyperedge_hit": {"1": True, "5": True},
                        "hyperedge_recall": {"5": 0.5},
                        "source_accuracy": False, "path_hit": False},
            "final_reference": {"hit": {"1": True, "5": True}},
        }}
        self.assertEqual("hyperedge_not_recalled", classify_result(result))


if __name__ == "__main__":
    unittest.main()
