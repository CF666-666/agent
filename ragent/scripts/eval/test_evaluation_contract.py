#!/usr/bin/env python3
"""Regression tests for the evaluation dataset contract."""

import json
import tempfile
import unittest
from pathlib import Path

from evaluation_contract import (
    EvaluationContractError,
    evidence_keys,
    load_jsonl_dataset,
    validate_conversation_record,
    validate_split_isolation,
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
            {"role": "user", "content": "它的压差正常范围呢？"},
        ],
        target_turn_index=1,
        conversation_type="cross_turn_reference",
        canonical_target_query="冷却泵入口过滤器压差正常范围是多少？",
        context_notes="它指代冷却泵入口过滤器",
    )
    record.pop("query")
    record.update(overrides)
    return record


class EvaluationContractTest(unittest.TestCase):

    def test_accepts_a_complete_single_turn_record(self):
        validate_single_turn_record(single_turn())

    def test_validates_declared_query_noise_metadata(self):
        noisy = single_turn(
            scene="colloquial", ragas_group="noise",
            query="主变油温九十五度往上走咋查？",
            canonical_query="变压器运行中油温异常升高至95℃以上，可能原因有哪些？",
            noise_type="unit_format",
            mutation_notes="设备简称与温度单位口语化",
        )
        validate_single_turn_record(noisy)
        with self.assertRaisesRegex(EvaluationContractError, "unsupported noise_type"):
            validate_single_turn_record({**noisy, "noise_type": "generic_prefix"})
        with self.assertRaisesRegex(EvaluationContractError, "must differ"):
            validate_single_turn_record({**noisy, "query": noisy["canonical_query"]})
        with self.assertRaisesRegex(EvaluationContractError, "generic prefix"):
            validate_single_turn_record({**noisy, "query": "师傅现场问：" + noisy["canonical_query"]})
        with self.assertRaisesRegex(EvaluationContractError, "must differ"):
            validate_single_turn_record({**noisy, "query": "  " + noisy["canonical_query"] + "  "})
        missing_notes = dict(noisy)
        missing_notes.pop("mutation_notes")
        with self.assertRaisesRegex(EvaluationContractError, "mutation_notes"):
            validate_single_turn_record(missing_notes)
        missing_all = dict(noisy)
        for field in ("canonical_query", "noise_type", "mutation_notes"):
            missing_all.pop(field)
        with self.assertRaisesRegex(EvaluationContractError, "query noise metadata requires"):
            validate_single_turn_record(missing_all)

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

    def test_accepts_user_only_conversation_and_rejects_assistant_fixture(self):
        user_only = conversation(turns=[
            {"role": "user", "content": "冷却泵流量低怎么办？"},
            {"role": "user", "content": "它的压差正常范围呢？"},
        ], target_turn_index=1)
        validate_conversation_record(user_only)
        assistant_fixture = conversation(turns=[
            {"role": "user", "content": "冷却泵流量低怎么办？"},
            {"role": "assistant", "content": "先检查入口过滤器。"},
            {"role": "user", "content": "它的压差正常范围呢？"},
        ], target_turn_index=2)
        with self.assertRaisesRegex(EvaluationContractError, "user turns only"):
            validate_conversation_record(assistant_fixture)

    def test_rejects_missing_r3_conversation_metadata(self):
        record = conversation()
        record.pop("canonical_target_query")
        with self.assertRaisesRegex(EvaluationContractError, "canonical_target_query"):
            validate_conversation_record(record)
        with self.assertRaisesRegex(EvaluationContractError, "conversation_type"):
            validate_conversation_record(conversation(conversation_type="unsupported"))

    def test_load_reports_line_specific_invalid_json(self):
        with tempfile.TemporaryDirectory() as directory:
            dataset = Path(directory) / "broken.jsonl"
            dataset.write_text('{"id":\n', encoding="utf-8")
            with self.assertRaisesRegex(EvaluationContractError, "broken.jsonl:1: invalid JSON"):
                load_jsonl_dataset(dataset)

    def test_split_isolation_accepts_disjoint_evidence(self):
        tuning = single_turn(id="tuning-001", split="tuning")
        frozen = single_turn(
            id="frozen-001", split="frozen", golden_source_ids=["manual-002"],
            provenance={"source_file": "datasets/other.jsonl", "source_record_id": "manual-002"})
        validate_split_isolation([tuning], [frozen])

    def test_split_isolation_rejects_shared_source_and_provenance(self):
        tuning = single_turn(id="tuning-001", split="tuning")
        frozen = single_turn(id="frozen-001", split="frozen")
        with self.assertRaisesRegex(EvaluationContractError, "source_id:manual-001"):
            validate_split_isolation([tuning], [frozen])

        frozen = single_turn(
            id="frozen-002", split="frozen", golden_source_ids=["manual-002"])
        with self.assertRaisesRegex(EvaluationContractError, "provenance:datasets/manual.jsonl#manual-001"):
            validate_split_isolation([tuning], [frozen])

    def test_split_isolation_rejects_shared_image_or_hyperedge(self):
        tuning_image = single_turn(
            id="tuning-image", split="tuning", scene="image",
            expected_channels=["IMAGE_SEMANTIC"], golden_image_paths=["images/pump.png"])
        frozen_image = single_turn(
            id="frozen-image", split="frozen", scene="image",
            expected_channels=["IMAGE_SEMANTIC"], golden_source_ids=["manual-002"],
            provenance={"source_file": "datasets/other.jsonl", "source_record_id": "manual-002"},
            golden_image_paths=["images/pump.png"])
        with self.assertRaisesRegex(EvaluationContractError, "image:images/pump.png"):
            validate_split_isolation([tuning_image], [frozen_image])

        tuning_relation = single_turn(
            id="tuning-relation", split="tuning", scene="relation",
            expected_channels=["HYPERGRAPH"], golden_hyperedge_ids=["edge-001"])
        frozen_relation = single_turn(
            id="frozen-relation", split="frozen", scene="relation",
            expected_channels=["HYPERGRAPH"], golden_source_ids=["manual-003"],
            provenance={"source_file": "datasets/third.jsonl", "source_record_id": "manual-003"},
            golden_hyperedge_ids=["edge-001"])
        with self.assertRaisesRegex(EvaluationContractError, "hyperedge:edge-001"):
            validate_split_isolation([tuning_relation], [frozen_relation])

    def test_split_isolation_rejects_legacy_records_and_exposes_evidence(self):
        legacy = single_turn(id="legacy-001")
        legacy.pop("schema_version")
        legacy.pop("case_type")
        legacy.pop("split")
        frozen = single_turn(
            id="frozen-001", split="frozen", golden_source_ids=["manual-002"],
            provenance={"source_file": "datasets/other.jsonl", "source_record_id": "manual-002"})
        with self.assertRaisesRegex(EvaluationContractError, "must declare schema_version"):
            validate_split_isolation([legacy], [frozen])

        keys = evidence_keys(single_turn(scene="image", expected_channels=["IMAGE_SEMANTIC"],
                                         golden_image_paths=["images/pump.png"]))
        self.assertIn("source_id:manual-001", keys)
        self.assertIn("provenance:datasets/manual.jsonl#manual-001", keys)
        self.assertIn("image:images/pump.png", keys)


if __name__ == "__main__":
    unittest.main()
