#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Archive a retrieval baseline with enough evidence to be reproduced safely.

The archiver deliberately accepts only schema-v3 reports.  It copies raw scene
reports and their merged result, verifies that the merge is not a hand-edited
summary, and emits a secret-free manifest.  The actual SSE execution remains
the responsibility of ``retrieval_eval.py``.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path
from typing import Any

from merge_eval_reports import REPORT_SCHEMA_VERSION, merge_documents


class BaselineArchiveError(ValueError):
    """Raised when reports cannot serve as one controlled baseline."""


def canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_report(path: Path) -> dict[str, Any]:
    try:
        report = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise BaselineArchiveError(f"report is missing: {path}") from error
    except json.JSONDecodeError as error:
        raise BaselineArchiveError(f"report is invalid JSON: {path}: {error.msg}") from error
    if report.get("schema_version") != REPORT_SCHEMA_VERSION:
        raise BaselineArchiveError(
            f"report must use schema {REPORT_SCHEMA_VERSION}: {path}")
    return report


def verify_merged_report(raw_paths: list[Path], merged_path: Path) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    raw_reports = [read_report(path) for path in raw_paths]
    merged = read_report(merged_path)
    recomputed = merge_documents(raw_reports, raw_paths)
    for field in ("mode", "dataset", "retrieval_options", "runtime",
                  "execution_fingerprint", "summary", "results"):
        if canonical(merged.get(field)) != canonical(recomputed.get(field)):
            raise BaselineArchiveError(
                f"merged report differs from raw reports in field {field}: {merged_path}")
    return raw_reports, merged


def build_manifest(raw_paths: list[Path], merged_path: Path, merged: dict[str, Any],
                   backend_image: str, service_url: str) -> dict[str, Any]:
    return {
        "baseline_archive_version": 1,
        "purpose": "pre-feature controlled retrieval baseline",
        "backend_image": backend_image,
        "service_url": service_url,
        "dataset": merged["dataset"],
        "retrieval_options": merged["retrieval_options"],
        "runtime": merged["runtime"],
        "execution_fingerprint": merged["execution_fingerprint"],
        "raw_reports": [
            {"file": path.name, "sha256": file_sha256(path)} for path in raw_paths
        ],
        "merged_report": {"file": merged_path.name, "sha256": file_sha256(merged_path)},
        "reproduction": {
            "runner": "scripts/eval/retrieval_eval.py",
            "merge_runner": "scripts/eval/merge_eval_reports.py",
            "contract_check": "scripts/eval/evaluation_contract.py",
            "notes": "Run the commands in commands.md; never record API keys in this archive.",
        },
    }


def commands_markdown(merged: dict[str, Any], raw_names: list[str], merged_name: str,
                      service_url: str) -> str:
    options = merged["retrieval_options"]
    runtime = merged["runtime"]
    common = [
        "--base-url", service_url,
        "--dataset", "scripts/eval/datasets/industrial_eval_v2.jsonl",
        "--label", str(options.get("label", "")),
        "--runtime-label", str(runtime.get("label", "")),
        "--request-timeout", str(runtime.get("request_timeout_seconds", "")),
    ]
    if not options.get("enableRewrite", True):
        common.append("--disable-rewrite")
    if not options.get("enableImage", True):
        common.append("--disable-image")
    if not options.get("enableHyperGraph", True):
        common.append("--disable-hypergraph")
    if not options.get("enableFusion", True):
        common.append("--disable-fusion")
    if options.get("retrievalOnly", False):
        common.append("--retrieval-only")
    option_text = " ".join(common)
    scenes = ["fact", "colloquial", "image", "relation"]
    rows = []
    for scene, raw_name in zip(scenes, raw_names):
        rows.append(
            "python scripts/eval/retrieval_eval.py "
            f"{option_text} --warmup-count 1 --scenes {scene} "
            f"--out scripts/eval/report/baselines/<run>/{raw_name}")
    merge_inputs = " ".join(f"scripts/eval/report/baselines/<run>/{name}" for name in raw_names)
    rows.append(
        "python scripts/eval/merge_eval_reports.py "
        f"--out scripts/eval/report/baselines/<run>/{merged_name} {merge_inputs}")
    return "# Controlled baseline reproduction\n\n```powershell\n" + "\n".join(rows) + "\n```\n"


def archive(raw_paths: list[Path], merged_path: Path, out_dir: Path,
            backend_image: str, service_url: str) -> dict[str, Any]:
    if not backend_image.strip():
        raise BaselineArchiveError("backend_image must be a non-empty immutable image identifier")
    if not service_url.strip():
        raise BaselineArchiveError("service_url must be a non-empty URL label")
    if out_dir.exists() and any(out_dir.iterdir()):
        raise BaselineArchiveError(f"archive directory must be empty: {out_dir}")

    _, merged = verify_merged_report(raw_paths, merged_path)
    out_dir.mkdir(parents=True, exist_ok=True)
    for path in [*raw_paths, merged_path]:
        shutil.copy2(path, out_dir / path.name)
    manifest = build_manifest(raw_paths, merged_path, merged, backend_image, service_url)
    (out_dir / "baseline-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (out_dir / "commands.md").write_text(
        commands_markdown(merged, [path.name for path in raw_paths], merged_path.name, service_url),
        encoding="utf-8")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description="archive a controlled Ragent retrieval baseline")
    parser.add_argument("--raw", type=Path, nargs="+", required=True, help="schema-v3 raw scene reports")
    parser.add_argument("--merged", type=Path, required=True, help="schema-v3 merged report")
    parser.add_argument("--out-dir", type=Path, required=True)
    parser.add_argument("--backend-image", required=True, help="immutable Docker image ID or digest")
    parser.add_argument("--service-url", default="http://localhost:9090/api/ragent")
    args = parser.parse_args()

    manifest = archive(args.raw, args.merged, args.out_dir, args.backend_image, args.service_url)
    print(json.dumps({"archive": str(args.out_dir), "dataset": manifest["dataset"],
                      "summary": read_report(args.merged)["summary"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
