import unittest
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from retrieval_eval import execution_status, run_warmups


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


if __name__ == "__main__":
    unittest.main()
