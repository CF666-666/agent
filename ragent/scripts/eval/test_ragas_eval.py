#!/usr/bin/env python3
"""Offline regression tests for the R5-C extended RAGAS runner.

These tests cover the pure functions in core.py without requiring a
running backend or the ragas evaluation models.
"""

import sys
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

import ragas_eval_core as core  # noqa: E402


def _record(**overrides):
    base = {
        "id": "v2-fact-001",
        "query": "冷却塔检修安全规范?",
        "golden_answer": "必须断电挂牌上锁",
        "scene": "fact",
    }
    base.update(overrides)
    return base


class RagasGroupMappingTest(unittest.TestCase):

    def test_standard_scene_mapping(self):
        self.assertEqual("text", core.ragas_group_of(_record(scene="fact")))
        self.assertEqual("image", core.ragas_group_of(_record(scene="image")))
        self.assertEqual("relation", core.ragas_group_of(_record(scene="relation")))
        self.assertEqual("noise", core.ragas_group_of(_record(scene="colloquial")))

    def test_explicit_ragas_group_wins(self):
        self.assertEqual("noise", core.ragas_group_of(_record(scene="colloquial", ragas_group="noise")))
        self.assertEqual("text", core.ragas_group_of(_record(scene="fact", ragas_group="text")))

    def test_legacy_chinese_scene_falls_back_to_text(self):
        self.assertEqual("text", core.ragas_group_of(_record(scene="安全规范", id=None)))
        self.assertEqual("text", core.ragas_group_of(_record(scene="故障诊断", id=None)))

    def test_unknown_scene_falls_back_to_text(self):
        self.assertEqual("text", core.ragas_group_of(_record(scene="whatever")))

    def test_invalid_explicit_group_raises(self):
        with self.assertRaises(ValueError):
            core.ragas_group_of(_record(ragas_group="bad_group"))


class CaseIdTest(unittest.TestCase):

    def test_prefers_id_field(self):
        self.assertEqual("v2-fact-001", core.case_id(_record(id="v2-fact-001")))

    def test_hash_fallback_distinguishes_same_query_different_golden(self):
        a = _record(id=None, query="same query", golden_source_ids=["image-a"])
        b = _record(id=None, query="same query", golden_source_ids=["image-b"])
        self.assertNotEqual(core.case_id(a), core.case_id(b))

    def test_hash_fallback_is_stable(self):
        a = _record(id=None, query="q", golden_answer="a")
        self.assertEqual(core.case_id(a), core.case_id(dict(a)))


class StratificationTest(unittest.TestCase):

    def _items(self):
        items = []
        for i in range(4):
            items.append(_record(id=f"fact-{i}", scene="fact"))
            items.append(_record(id=f"noise-{i}", scene="colloquial", ragas_group="noise"))
            items.append(_record(id=f"image-{i}", scene="image"))
            items.append(_record(id=f"relation-{i}", scene="relation"))
        return items

    def test_stratified_sample_is_deterministic(self):
        items = self._items()
        self.assertEqual(
            [r["id"] for r in core.stratified_sample(items, 2, 202603)],
            [r["id"] for r in core.stratified_sample(items, 2, 202603)],
        )

    def test_stratified_sample_balanced(self):
        selected = core.stratified_sample(self._items(), 2, 202603)
        groups = {}
        for record in selected:
            groups.setdefault(core.ragas_group_of(record), []).append(record)
        self.assertEqual(set(groups), {"text", "noise", "image", "relation"})
        for records in groups.values():
            self.assertEqual(2, len(records))

    def test_group_insufficient_does_not_crash(self):
        items = [_record(id=f"fact-{i}", scene="fact") for i in range(3)]
        selected = core.stratified_sample(items, 5, 1)
        self.assertEqual(3, len(selected))


class ConfidenceIntervalTest(unittest.TestCase):

    def test_known_mean_and_none_ci_for_single(self):
        mean, ci = core.mean_ci([0.5])
        self.assertAlmostEqual(0.5, mean)
        self.assertIsNone(ci)

    def test_empty_returns_none_mean(self):
        # 无有效样本时 mean 为 None,与"实际得分 0"区分
        mean, ci = core.mean_ci([])
        self.assertIsNone(mean)
        self.assertIsNone(ci)

    def test_ignores_nan(self):
        mean, ci = core.mean_ci([0.2, 0.4, float("nan")])
        self.assertAlmostEqual(0.3, mean)
        self.assertIsNotNone(ci)

    def test_t_critical_matches_known_value(self):
        # t(14, 0.975) ~= 2.144787
        self.assertAlmostEqual(2.144787, core.t_critical_0975(15), places=3)

    def test_ci_widens_for_small_n(self):
        # 小样本(n=3)的 CI 半宽应大于大样本(n=30)同一组数值
        values_small = [0.5, 0.6, 0.7]
        values_large = [0.5, 0.6, 0.7] * 10
        _, ci_small = core.mean_ci(values_small)
        _, ci_large = core.mean_ci(values_large)
        self.assertGreater(ci_small, ci_large)


class CheckpointTest(unittest.TestCase):

    def test_roundtrip_and_tolerates_trailing_garbage(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "report.checkpoint.jsonl"
            core.append_checkpoint(path, {"case_id": "a", "status": "collected", "answer": "x"})
            core.append_checkpoint(path, {"case_id": "b", "status": "failed_collect"})
            # 模拟中断写半行
            with path.open("a", encoding="utf-8") as f:
                f.write('{"case_id": "c", "status": "collected"')
            loaded = core.load_checkpoint(path)
            self.assertIn("a", loaded)
            self.assertIn("b", loaded)
            self.assertNotIn("c", loaded)
            self.assertEqual("collected", loaded["a"]["status"])

    def test_missing_checkpoint_returns_empty(self):
        self.assertEqual({}, core.load_checkpoint(Path("/nonexistent/checkpoint.jsonl")))


class SelectionTest(unittest.TestCase):

    def test_filters_conversation_records(self):
        items = [
            _record(id="single", scene="fact"),
            {"id": "conv", "case_type": "conversation", "scene": "colloquial", "ragas_group": "noise",
             "golden_answer": "x", "turns": [{"role": "user", "content": "a"}, {"role": "user", "content": "b"}]},
        ]
        selected = core.select_items(items, None, 10, 202603)
        self.assertEqual(["single"], [r["id"] for r in selected])


if __name__ == "__main__":
    unittest.main()
