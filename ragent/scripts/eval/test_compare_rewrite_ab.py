import copy
import sys
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

import compare_rewrite_ab  # noqa: E402


def report(rewrite: bool):
    return {
        "schema_version": 1,
        "mode": "rewrite-on" if rewrite else "rewrite-off",
        "dataset": {"path": "frozen.jsonl", "sha256": "a" * 64},
        "retrieval_options": {
            "enableRewrite": rewrite, "enableImage": False,
            "enableHyperGraph": False, "enableFusion": True,
            "retrievalOnly": False,
        },
        "runtime": {
            "request_timeout_seconds": 60, "warmup_count": 1,
            "backend_image": "sha256:" + "b" * 64,
        },
        "execution_fingerprint": {"sha256": "f" * 64},
        "evaluation_slice": {"count": 2},
        "warmup": {"requested_count": 1, "executed_count": 1,
                   "results": [{"dataset_id": "warmup", "ok": True, "status": "received"}]},
        "summary": {"hit_rate": {"@1": 0.5, "@3": 0.5, "@5": 1.0}, "mrr": 0.6},
        "results": [
            {"dataset_id": "c-1", "target_query": "它呢？", "ok": True, "mrr": 0.5},
            {"dataset_id": "c-2", "target_query": "这个呢？", "ok": True, "mrr": 0.7},
        ],
    }


class RewriteAbTest(unittest.TestCase):

    def test_accepts_pair_that_differs_only_by_rewrite_switch(self):
        off, on = report(False), report(True)
        on["summary"] = {"hit_rate": {"@1": 1.0, "@3": 1.0, "@5": 1.0}, "mrr": 0.9}
        on["results"] = [
            {"dataset_id": "c-1", "target_query": "它呢？", "ok": True, "mrr": 1.0},
            {"dataset_id": "c-2", "target_query": "这个呢？", "ok": True, "mrr": 0.8},
        ]

        comparison = compare_rewrite_ab.compare(off, on)

        self.assertEqual(0.3, comparison["delta"]["mrr_absolute"])
        self.assertEqual(0.5, comparison["delta"]["hit_at_1_absolute"])
        self.assertEqual(2, len(comparison["paired_cases"]))

    def test_rejects_any_runtime_dataset_or_case_order_mismatch(self):
        mutations = []
        changed = copy.deepcopy(report(True))
        changed["dataset"]["sha256"] = "c" * 64
        mutations.append((changed, "dataset"))
        changed = copy.deepcopy(report(True))
        changed["runtime"]["warmup_count"] = 0
        mutations.append((changed, "runtime"))
        changed = copy.deepcopy(report(True))
        changed["execution_fingerprint"]["sha256"] = "e" * 64
        mutations.append((changed, "execution fingerprint"))
        changed = copy.deepcopy(report(True))
        changed["results"].reverse()
        mutations.append((changed, "case order"))
        for changed, message in mutations:
            with self.subTest(message=message):
                with self.assertRaisesRegex(ValueError, message):
                    compare_rewrite_ab.compare(report(False), changed)

    def test_rejects_non_immutable_backend_image(self):
        off = report(False)
        on = report(True)
        off["runtime"]["backend_image"] = "ragent-stack-backend:latest"
        on["runtime"]["backend_image"] = "ragent-stack-backend:latest"
        with self.assertRaisesRegex(ValueError, "immutable backend image"):
            compare_rewrite_ab.compare(off, on)

    def test_rejects_failed_warmup(self):
        on = report(True)
        on["warmup"]["results"][0]["ok"] = False
        with self.assertRaisesRegex(ValueError, "warmup must succeed"):
            compare_rewrite_ab.compare(report(False), on)


if __name__ == "__main__":
    unittest.main()
