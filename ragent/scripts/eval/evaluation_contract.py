#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Validation contract for reproducible Ragent evaluation datasets.

The contract deliberately uses only the Python standard library so every
retrieval and RAGAS runner can reject malformed data before issuing requests.
Legacy single-turn data without the new optional fields remains valid; newly
created sets should set ``schema_version`` to :data:`DATASET_SCHEMA_VERSION`
and declare a ``split``.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Iterable


DATASET_SCHEMA_VERSION = 1
SINGLE_TURN_SCENES = frozenset({"fact", "colloquial", "image", "relation"})
SPLITS = frozenset({"legacy", "tuning", "frozen"})
RAGAS_GROUPS = frozenset({"text", "noise", "image", "relation"})
CHANNELS = frozenset({
    "VECTOR_GLOBAL", "INTENT_DIRECTED", "KEYWORD_ES", "HYBRID",
    "IMAGE_SEMANTIC", "HYPERGRAPH",
})
MESSAGE_ROLES = frozenset({"user", "assistant"})


class EvaluationContractError(ValueError):
    """Raised when an evaluation record cannot support reproducible metrics."""


def _fail(location: str, message: str) -> None:
    raise EvaluationContractError(f"{location}: {message}")


def _non_empty_string(value: Any, location: str, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        _fail(location, f"{field} must be a non-empty string")
    return value


def _non_empty_string_list(value: Any, location: str, field: str) -> list[str]:
    if not isinstance(value, list) or not value:
        _fail(location, f"{field} must be a non-empty list")
    for index, item in enumerate(value):
        _non_empty_string(item, location, f"{field}[{index}]")
    return value


def _validate_common(record: dict[str, Any], location: str) -> None:
    if not isinstance(record, dict):
        _fail(location, "record must be a JSON object")
    _non_empty_string(record.get("id"), location, "id")
    _non_empty_string(record.get("scene"), location, "scene")
    _non_empty_string(record.get("golden_answer"), location, "golden_answer")

    schema_version = record.get("schema_version")
    if schema_version is not None and schema_version != DATASET_SCHEMA_VERSION:
        _fail(location, f"schema_version must be {DATASET_SCHEMA_VERSION} when present")

    split = record.get("split", "legacy")
    if split not in SPLITS:
        _fail(location, f"split must be one of {sorted(SPLITS)}")

    expected_channels = _non_empty_string_list(record.get("expected_channels"), location, "expected_channels")
    unknown_channels = sorted(set(expected_channels) - CHANNELS)
    if unknown_channels:
        _fail(location, f"expected_channels contains unsupported values: {unknown_channels}")
    _non_empty_string_list(record.get("golden_source_ids"), location, "golden_source_ids")

    provenance = record.get("provenance")
    if not isinstance(provenance, dict):
        _fail(location, "provenance must be an object")
    _non_empty_string(provenance.get("source_file"), location, "provenance.source_file")
    _non_empty_string(provenance.get("source_record_id"), location, "provenance.source_record_id")

    ragas_group = record.get("ragas_group")
    if ragas_group is not None and ragas_group not in RAGAS_GROUPS:
        _fail(location, f"ragas_group must be one of {sorted(RAGAS_GROUPS)}")
    if "business_tags" in record:
        _non_empty_string_list(record["business_tags"], location, "business_tags")


def validate_single_turn_record(record: dict[str, Any], location: str = "record") -> None:
    """Validate one retrieval/RAGAS single-turn record.

    New records may explicitly set ``case_type`` to ``single_turn``. Omitting
    it preserves compatibility with the checked-in v2 baseline dataset.
    """
    _validate_common(record, location)
    if record.get("case_type", "single_turn") != "single_turn":
        _fail(location, "case_type must be single_turn")
    _non_empty_string(record.get("query"), location, "query")
    scene = record["scene"]
    if scene not in SINGLE_TURN_SCENES:
        _fail(location, f"scene must be one of {sorted(SINGLE_TURN_SCENES)}")
    if scene == "image":
        _non_empty_string_list(record.get("golden_image_paths"), location, "golden_image_paths")
    if scene == "relation":
        _non_empty_string_list(record.get("golden_hyperedge_ids"), location, "golden_hyperedge_ids")
        if "golden_source_documents" in record:
            _non_empty_string_list(
                record.get("golden_source_documents"), location, "golden_source_documents")


def validate_conversation_record(record: dict[str, Any], location: str = "record") -> None:
    """Validate one multi-turn coreference evaluation case.

    ``target_turn_index`` identifies the user turn scored by a future
    conversation runner. Keeping it explicit prevents a runner from silently
    evaluating a different turn after the dataset evolves.
    """
    _validate_common(record, location)
    if record.get("case_type") != "conversation":
        _fail(location, "case_type must be conversation")
    if record.get("scene") != "colloquial":
        _fail(location, "conversation scene must be colloquial")
    turns = record.get("turns")
    if not isinstance(turns, list) or len(turns) < 2:
        _fail(location, "turns must contain at least two messages")
    for index, turn in enumerate(turns):
        turn_location = f"{location}.turns[{index}]"
        if not isinstance(turn, dict):
            _fail(turn_location, "turn must be an object")
        if turn.get("role") not in MESSAGE_ROLES:
            _fail(turn_location, f"role must be one of {sorted(MESSAGE_ROLES)}")
        _non_empty_string(turn.get("content"), turn_location, "content")
    target_index = record.get("target_turn_index")
    if not isinstance(target_index, int) or isinstance(target_index, bool):
        _fail(location, "target_turn_index must be an integer")
    if target_index < 0 or target_index >= len(turns):
        _fail(location, "target_turn_index must point to a turn")
    if turns[target_index].get("role") != "user":
        _fail(location, "target_turn_index must point to a user turn")
    if target_index != len(turns) - 1:
        _fail(location, "target_turn_index must be the final turn")


def validate_records(records: Iterable[dict[str, Any]], kind: str = "single_turn") -> list[dict[str, Any]]:
    """Validate records and return them unchanged for convenient runner use."""
    if kind not in {"single_turn", "conversation"}:
        raise ValueError("kind must be single_turn or conversation")
    validator = validate_single_turn_record if kind == "single_turn" else validate_conversation_record
    validated = list(records)
    seen_ids: set[str] = set()
    for line_number, record in enumerate(validated, 1):
        location = f"record {line_number}"
        validator(record, location)
        record_id = record["id"]
        if record_id in seen_ids:
            _fail(location, f"duplicate id: {record_id}")
        seen_ids.add(record_id)
    if not validated:
        raise EvaluationContractError("dataset must contain at least one record")
    return validated


def validate_dataset_split(
        records: Iterable[dict[str, Any]], expected_split: str,
        kind: str = "single_turn") -> list[dict[str, Any]]:
    """Validate one newly authored tuning or frozen dataset.

    Historical v2 data is intentionally accepted by :func:`validate_records`
    as ``legacy``.  It must not, however, be used for tuning/frozen isolation:
    otherwise a permissive compatibility path could silently dilute an A/B
    result.
    """
    if expected_split not in {"tuning", "frozen"}:
        raise ValueError("expected_split must be tuning or frozen")
    validated = validate_records(records, kind)
    expected_case_type = "single_turn" if kind == "single_turn" else "conversation"
    for index, record in enumerate(validated, 1):
        location = f"record {index} ({record['id']})"
        if record.get("schema_version") != DATASET_SCHEMA_VERSION:
            _fail(location, "tuning/frozen records must declare schema_version")
        if record.get("case_type") != expected_case_type:
            _fail(location, f"tuning/frozen records must declare case_type {expected_case_type}")
        if record.get("split") != expected_split:
            _fail(location, f"split must be {expected_split}")
    return validated


def evidence_keys(record: dict[str, Any]) -> set[str]:
    """Return canonical source-evidence keys used for split-isolation checks.

    Namespacing keeps identifiers from independent systems from colliding while
    enforcing the four evidence identities that can leak evaluation knowledge:
    source IDs, source records, image assets and hyperedges.
    """
    provenance = record["provenance"]
    keys = {f"source_id:{value}" for value in record["golden_source_ids"]}
    keys.add(
        "provenance:"
        f"{provenance['source_file']}#{provenance['source_record_id']}")
    keys.update(f"image:{value}" for value in record.get("golden_image_paths", []))
    keys.update(f"hyperedge:{value}" for value in record.get("golden_hyperedge_ids", []))
    return keys


def validate_split_isolation(
        tuning_records: Iterable[dict[str, Any]], frozen_records: Iterable[dict[str, Any]],
        kind: str = "single_turn") -> None:
    """Reject tuning/frozen sets that share a scored source, image or hyperedge."""
    tuning = validate_dataset_split(tuning_records, "tuning", kind)
    frozen = validate_dataset_split(frozen_records, "frozen", kind)

    def index_records(records: Iterable[dict[str, Any]]) -> dict[str, list[str]]:
        indexed: dict[str, list[str]] = {}
        for record in records:
            for key in evidence_keys(record):
                indexed.setdefault(key, []).append(record["id"])
        return indexed

    tuning_index = index_records(tuning)
    frozen_index = index_records(frozen)
    overlaps = sorted(set(tuning_index) & set(frozen_index))
    if overlaps:
        details = "; ".join(
            f"{key} (tuning={tuning_index[key]}, frozen={frozen_index[key]})"
            for key in overlaps)
        raise EvaluationContractError(
            "tuning/frozen evidence leakage detected: " + details)


def load_jsonl_dataset(path: Path, kind: str = "single_turn") -> list[dict[str, Any]]:
    """Read and validate a UTF-8 JSONL dataset with line-specific errors."""
    records: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            stripped = line.strip()
            if not stripped:
                continue
            try:
                record = json.loads(stripped)
            except json.JSONDecodeError as error:
                raise EvaluationContractError(
                    f"{path}:{line_number}: invalid JSON: {error.msg}") from error
            records.append(record)
    try:
        return validate_records(records, kind)
    except EvaluationContractError as error:
        raise EvaluationContractError(f"{path}: {error}") from error


def main() -> None:
    import argparse

    parser = argparse.ArgumentParser(description="validate an Ragent evaluation JSONL dataset")
    parser.add_argument("dataset", type=Path, nargs="?")
    parser.add_argument("--kind", choices=("single_turn", "conversation"), default="single_turn")
    parser.add_argument("--tuning", type=Path, help="new tuning JSONL dataset for isolation validation")
    parser.add_argument("--frozen", type=Path, help="new frozen JSONL dataset for isolation validation")
    args = parser.parse_args()
    if args.tuning is not None or args.frozen is not None:
        if args.dataset is not None or args.tuning is None or args.frozen is None:
            parser.error("use --tuning and --frozen together, without dataset")
        tuning = load_jsonl_dataset(args.tuning, args.kind)
        frozen = load_jsonl_dataset(args.frozen, args.kind)
        validate_split_isolation(tuning, frozen, args.kind)
        print(
            f"valid isolated {args.kind} datasets: "
            f"{args.tuning} ({len(tuning)} records), {args.frozen} ({len(frozen)} records)")
        return
    if args.dataset is None:
        parser.error("dataset is required unless --tuning and --frozen are provided")
    records = load_jsonl_dataset(args.dataset, args.kind)
    print(f"valid {args.kind} dataset: {args.dataset} ({len(records)} records)")


if __name__ == "__main__":
    main()
