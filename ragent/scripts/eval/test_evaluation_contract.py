#!/usr/bin/env python3
"""Regression tests for the evaluation dataset contract."""

import json
import tempfile
import unittest
from pathlib import Path

from evaluation_contract import (
    EvaluationContractError,
    load_jsonl_dataset,
    validate_conversation_record,
    validate_single_turn_record,
)


def single_turn(**overrides):
    record = {
        "schema_version": 1,
        "case_type": "single_turn",
        "id": "fact-001",
        "query": "设备参数是多少？",
        "golden_answer": "额定压力为 1MPa。",
        "scene": "fact",
        "split": "frozen",
        "expected_channels": ["VECTOR_GLOBAL"],
        "golden_source_ids": ["manual-001"],
        "provenance": {"source_file": "datasets/manual.jsonl", "source_record_id": "manual-001"},
        "ragas_group": "text",
        "business_tags": ["维护保养"],
    }
    record.update(overrides)
    return record


def conversation(**overrides):
    record = single_turn(
        case_type="conversation",
        id="conversation-001",
        scene="colloquial",
        ragas_group="noise",
        turns=[
            {"role": "user", "content": "冷却泵流量低怎么办？"},
            {"role": "assistant", "content": "先检查入口过滤器。"},
            {"role": "user", "content": "它的压差正常范围呢？"},
        ],
        target_turn_index=2,
    )
    record.pop("query")
    record.update(overrides)
    return record


class EvaluationContractTest(unittest.TestCase):

    def test_accepts_a_complete_single_turn_record(self):
        validate_single_turn_record(single_turn())

    def test_accepts_legacy_v2_style_record(self):
        legacy = single_turn()
        for field in ("schema_version", "case_type", "split", "ragas_group", "business_tags"):
            legacy.pop(field)
        validate_single_turn_record(legacy)

    def test_rejects_image_case_without_image_evidence(self):
        with self.assertRaisesRegex(EvaluationContractError, "golden_image_paths"):
            validate_single_turn_record(single_turn(scene="image", expected_channels=["IMAGE_SEMANTIC"]))

    def test_rejects_relation_case_without_hyperedge_evidence(self):
        with self.assertRaisesRegex(EvaluationContractError, "golden_hyperedge_ids"):
            validate_single_turn_record(single_turn(scene="relation", expected_channels=["HYPERGRAPH"]))

    def test_rejects_unknown_channel_and_duplicate_ids(self):
        with self.assertRaisesRegex(EvaluationContractError, "unsupported values"):
            validate_single_turn_record(single_turn(expected_channels=["UNKNOWN"]))

        with tempfile.TemporaryDirectory() as directory:
            dataset = Path(directory) / "duplicate.jsonl"
            dataset.write_text(
                "\n".join(json.dumps(single_turn(), ensure_ascii=False) for _ in range(2)) + "\n",
                encoding="utf-8")
            with self.assertRaisesRegex(EvaluationContractError, "duplicate id"):
                load_jsonl_dataset(dataset)

    def test_accepts_conversation_and_rejects_non_final_target(self):
        validate_conversation_record(conversation())
        with self.assertRaisesRegex(EvaluationContractError, "final turn"):
            validate_conversation_record(conversation(target_turn_index=0))

    def test_load_reports_line_specific_invalid_json(self):
        with tempfile.TemporaryDirectory() as directory:
            dataset = Path(directory) / "broken.jsonl"
            dataset.write_text('{"id":\n', encoding="utf-8")
            with self.assertRaisesRegex(EvaluationContractError, "broken.jsonl:1: invalid JSON"):
                load_jsonl_dataset(dataset)


if __name__ == "__main__":
    unittest.main()
