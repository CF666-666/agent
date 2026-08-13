#!/usr/bin/env python3
"""Regression tests for controlled retrieval baseline archiving."""

import json
import tempfile
import unittest
from pathlib import Path

from archive_baseline import BaselineArchiveError, archive
from merge_eval_reports import merge_documents


def report(scene: str, case_id: str) -> dict:
    return {
        "schema_version": 3,
        "mode": "rewrite-off",
        "dataset": {"path": "datasets/test.jsonl", "sha256": "d" * 64},
        "retrieval_options": {"label": "R0-E", "enableRewrite": False,
                              "enableImage": True, "enableHyperGraph": True,
                              "enableFusion": True, "retrievalOnly": True},
        "runtime": {"label": "controlled", "request_timeout_seconds": 10},
        "execution_fingerprint": {"sha256": "f" * 64},
        "evaluation_slice": {"scenes": scene, "offset": 0, "limit": 0, "count": 1},
        "summary": {},
        "results": [{"case_id": case_id, "scene": scene, "ok": True,
                     "retrieval_status": "received", "latency_ms": 10,
                     "hit": {"1": True, "3": True, "5": True}, "mrr": 1.0,
                     "channel_hit": True, "source_id_hit": True}],
    }


class BaselineArchiveTest(unittest.TestCase):

    def write_json(self, path: Path, payload: dict) -> None:
        path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def test_archives_verified_raw_and_merged_reports(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw_path = root / "raw.json"
            merged_path = root / "merged.json"
            raw = report("fact", "case-001")
            self.write_json(raw_path, raw)
            merged = merge_documents([raw], [raw_path])
            self.write_json(merged_path, merged)

            manifest = archive([raw_path], merged_path, root / "archive", "sha256:image", "http://service")

            self.assertEqual("sha256:image", manifest["backend_image"])
            self.assertTrue((root / "archive" / "raw.json").is_file())
            self.assertTrue((root / "archive" / "merged.json").is_file())
            self.assertIn("--retrieval-only", (root / "archive" / "commands.md").read_text(encoding="utf-8"))

    def test_rejects_hand_edited_merged_summary_and_nonempty_target(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw_path = root / "raw.json"
            merged_path = root / "merged.json"
            raw = report("fact", "case-001")
            self.write_json(raw_path, raw)
            self.write_json(merged_path, {**raw, "summary": {"total": 99}})
            with self.assertRaisesRegex(BaselineArchiveError, "merged report differs"):
                archive([raw_path], merged_path, root / "archive", "sha256:image", "http://service")

            target = root / "occupied"
            target.mkdir()
            (target / "old.txt").write_text("old", encoding="utf-8")
            with self.assertRaisesRegex(BaselineArchiveError, "must be empty"):
                archive([raw_path], merged_path, target, "sha256:image", "http://service")


if __name__ == "__main__":
    unittest.main()
