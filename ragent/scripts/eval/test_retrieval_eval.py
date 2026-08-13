import unittest
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from retrieval_eval import execution_status, relation_metrics, run_warmups


class WarmupRetrievalTest(unittest.TestCase):

    def test_warmup_uses_requested_prefix_without_mutating_evaluation_items(self):
        items = [
            {"scene": "fact", "query": "first", "golden_answer": "one"},
            {"scene": "fact", "query": "second", "golden_answer": "two"},
            {"scene": "fact", "query": "third", "golden_answer": "three"},
        ]
        seen_queries = []

        def retrieve(query):
            seen_queries.append(query)
            return [{"id": query}], "received", 12, None

        warmups = run_warmups(items, 2, retrieve)

        self.assertEqual(["first", "second"], seen_queries)
        self.assertEqual(2, len(warmups))
        self.assertEqual("received", warmups[0]["retrieval_status"])
        self.assertEqual(["first", "second", "third"], [item["query"] for item in items])

    def test_warmup_treats_negative_count_as_zero(self):
        calls = []

        warmups = run_warmups(
            [{"scene": "fact", "query": "first", "golden_answer": "one"}],
            -1,
            lambda query: calls.append(query),
        )

        self.assertEqual([], warmups)
        self.assertEqual([], calls)

    def test_channel_timeout_is_excluded_from_quality_metrics(self):
        self.assertEqual(
            "channel_timed_out",
            execution_status({"channels": [{"status": "TIMED_OUT"}]}, "received"),
        )

    def test_request_timeout_takes_precedence_over_channel_status(self):
        self.assertEqual(
            "timed_out",
            execution_status({"timedOut": True, "channels": [{"status": "COMPLETED"}]}, "received"),
        )

    def test_channel_degraded_is_excluded_from_quality_metrics(self):
        self.assertEqual(
            "channel_degraded",
            execution_status({"channels": [{"status": "DEGRADED"}]}, "received"),
        )

    def test_relation_metrics_rank_hyperedge_evidence_and_validate_source(self):
        references = [
            {
                "type": "HYPERGRAPH",
                "extra": {
                    "relationEvidence": [
                        {"hyperEdgeId": "edge-other", "sourceDocument": "manual-b"}
                    ]
                },
            },
            {
                "type": "HYPERGRAPH",
                "extra": {
                    "relationEvidence": [
                        {"hyperEdgeId": "edge-a", "sourceDocument": "manual-a"},
                        {"hyperEdgeId": "edge-b", "sourceDocument": "manual-a"},
                    ]
                },
            },
        ]

        result = relation_metrics(
            references,
            ["edge-a", "edge-b"],
            ["manual-a"],
        )

        self.assertFalse(result["hyperedge_hit"][1])
        self.assertTrue(result["hyperedge_hit"][3])
        self.assertEqual(0.0, result["hyperedge_recall"][1])
        self.assertEqual(1.0, result["hyperedge_recall"][3])
        self.assertTrue(result["path_hit"])
        self.assertTrue(result["source_accuracy"])

    def test_relation_metrics_do_not_accept_unrelated_source(self):
        references = [{
            "type": "HYPERGRAPH",
            "extra": {
                "relationEvidence": [
                    {"hyperEdgeId": "edge-a", "sourceDocument": "manual-b"}
                ]
            },
        }]

        result = relation_metrics(references, ["edge-a"], ["manual-a"])

        self.assertTrue(result["hyperedge_hit"][1])
        self.assertFalse(result["source_accuracy"])


if __name__ == "__main__":
    unittest.main()
