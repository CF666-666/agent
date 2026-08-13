import sys
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

import conversation_eval  # noqa: E402


def case(turns=None, target=1):
    return {
        "id": "conversation-001",
        "turns": turns or [
            {"role": "user", "content": "汽轮机振动突然增大怎么排查？"},
            {"role": "user", "content": "它超过0.08毫米时呢？"},
        ],
        "target_turn_index": target,
        "golden_answer": "检查轴系不平衡、动静摩擦和轴承状态。",
        "golden_source_ids": ["power_energy"],
        "expected_channels": ["VECTOR_GLOBAL"],
    }


class ConversationRunnerTest(unittest.TestCase):

    def test_fixed_warmup_must_complete_with_persisted_nonempty_answer(self):
        success = conversation_eval.run_warmup(
            "固定预热问题",
            lambda question, conversation_id: {
                "conversation_id": "warmup-1", "answer": "预热回答", "references": [],
                "retrieval_status": "received", "latency_ms": 10, "execution": None,
                "completed": True, "persisted": True,
            })
        self.assertTrue(success["ok"])
        with self.assertRaisesRegex(RuntimeError, "warmup failed"):
            conversation_eval.run_warmup(
                "固定预热问题",
                lambda question, conversation_id: {
                    "conversation_id": "warmup-2", "answer": "", "references": [],
                    "retrieval_status": "timed_out", "latency_ms": 18000, "execution": None,
                    "completed": False, "persisted": False,
                })

    def test_runs_user_turns_in_one_conversation_and_scores_only_target(self):
        calls = []

        def chat(question, conversation_id):
            calls.append((question, conversation_id))
            turn = len(calls) - 1
            return {
                "conversation_id": "server-conversation-1",
                "answer": f"answer-{turn}",
                "references": [{"type": "TEXT", "snippet": "轴系不平衡和轴承状态", "extra": {"sourceId": "power_energy"}}],
                "retrieval_status": "received",
                "latency_ms": 10 + turn,
                "execution": {"channels": []},
                "completed": True,
                "persisted": True,
            }

        result = conversation_eval.run_conversation_case(case(), chat)

        self.assertEqual([
            ("汽轮机振动突然增大怎么排查？", None),
            ("它超过0.08毫米时呢？", "server-conversation-1"),
        ], calls)
        self.assertEqual(2, len(result["turns"]))
        self.assertFalse(result["turns"][0]["scored"])
        self.assertTrue(result["turns"][1]["scored"])
        self.assertEqual("它超过0.08毫米时呢？", result["target_query"])
        self.assertEqual(["power_energy"], result["golden_source_ids"])
        self.assertTrue(result["source_id_hit"])
        self.assertEqual("轴系不平衡和轴承状态", result["turns"][1]["references"][0]["snippet"])

    def test_stops_before_target_when_history_turn_does_not_finish(self):
        calls = []

        def chat(question, conversation_id):
            calls.append(question)
            return {
                "conversation_id": "server-conversation-1", "answer": "",
                "references": [], "retrieval_status": "timeout", "latency_ms": 100,
                "execution": None, "completed": False,
                "persisted": False,
            }

        result = conversation_eval.run_conversation_case(case(), chat)

        self.assertEqual(["汽轮机振动突然增大怎么排查？"], calls)
        self.assertFalse(result["ok"])
        self.assertEqual("history_turn_failed", result["status"])
        self.assertEqual(0, result["failed_turn_index"])

    def test_rejects_conversation_id_change_between_turns(self):
        ids = iter(("conversation-a", "conversation-b"))

        def chat(question, conversation_id):
            return {
                "conversation_id": next(ids), "answer": "ok", "references": [],
                "retrieval_status": "received", "latency_ms": 10,
                "execution": None, "completed": True,
                "persisted": True,
            }

        with self.assertRaisesRegex(ValueError, "conversation id changed"):
            conversation_eval.run_conversation_case(case(), chat)

    def test_rejects_finished_history_with_empty_answer_or_rejected_status(self):
        for status, answer in (("received", ""), ("rejected", "not accepted")):
            with self.subTest(status=status, answer=answer):
                calls = []

                def chat(question, conversation_id):
                    calls.append(question)
                    return {
                        "conversation_id": "server-conversation-1", "answer": answer,
                        "references": [], "retrieval_status": status, "latency_ms": 10,
                        "execution": None, "completed": True, "persisted": True,
                    }

                result = conversation_eval.run_conversation_case(case(), chat)
                self.assertFalse(result["ok"])
                self.assertEqual("history_turn_failed", result["status"])
                self.assertEqual(1, len(calls))

    def test_rejects_history_not_confirmed_in_persistent_memory(self):
        def chat(question, conversation_id):
            return {
                "conversation_id": "server-conversation-1", "answer": "normal answer",
                "references": [], "retrieval_status": "received", "latency_ms": 10,
                "execution": None, "completed": True, "persisted": False,
            }

        result = conversation_eval.run_conversation_case(case(), chat)
        self.assertEqual("history_turn_failed", result["status"])
        self.assertFalse(result["turns"][0]["persisted"])


if __name__ == "__main__":
    unittest.main()
