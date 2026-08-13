#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Stable, secret-free runtime fingerprints for evaluation reports."""

from __future__ import annotations

import hashlib
import json
import platform
import subprocess
import sys
from pathlib import Path
from typing import Any


FINGERPRINT_VERSION = 1
SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parents[1]
DEFAULT_PROFILE = SCRIPT_DIR / "runtime-profiles" / "local-default.json"
DEFAULT_APPLICATION_CONFIG = PROJECT_ROOT / "bootstrap" / "src" / "main" / "resources" / "application.yaml"


class RuntimeFingerprintError(ValueError):
    """Raised when a configured runtime profile is incomplete or unsafe."""


def canonical_hash(value: Any) -> str:
    payload = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def display_path(path: Path) -> str:
    try:
        return path.resolve().relative_to(PROJECT_ROOT.resolve()).as_posix()
    except ValueError:
        return path.as_posix()


def file_descriptor(path: Path) -> dict[str, str]:
    if not path.is_file():
        raise RuntimeFingerprintError(f"required fingerprint file is missing: {path}")
    return {"path": display_path(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def _git_output(*args: str) -> str | None:
    try:
        return subprocess.check_output(
            ["git", "-C", str(PROJECT_ROOT), *args], text=True, stderr=subprocess.DEVNULL
        ).strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def git_metadata() -> dict[str, Any]:
    revision = _git_output("rev-parse", "HEAD")
    status = _git_output("status", "--porcelain")
    return {
        "revision": revision or "unavailable",
        "worktree_dirty": bool(status) if status is not None else None,
    }


def load_runtime_profile(path: Path) -> dict[str, Any]:
    try:
        profile = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise RuntimeFingerprintError(f"runtime profile is missing: {path}") from error
    except json.JSONDecodeError as error:
        raise RuntimeFingerprintError(f"runtime profile is not valid JSON: {path}: {error.msg}") from error
    if not isinstance(profile, dict):
        raise RuntimeFingerprintError("runtime profile must be a JSON object")
    if profile.get("profile_version") != 1:
        raise RuntimeFingerprintError("runtime profile profile_version must be 1")
    if not isinstance(profile.get("name"), str) or not profile["name"].strip():
        raise RuntimeFingerprintError("runtime profile name must be a non-empty string")
    models = profile.get("configured_models")
    if not isinstance(models, dict) or not models:
        raise RuntimeFingerprintError("runtime profile configured_models must be a non-empty object")
    for component, definition in models.items():
        if not isinstance(component, str) or not isinstance(definition, dict):
            raise RuntimeFingerprintError("configured_models must map names to objects")
        for required in ("model_id", "provider", "model"):
            if not isinstance(definition.get(required), str) or not definition[required].strip():
                raise RuntimeFingerprintError(
                    f"configured_models.{component}.{required} must be a non-empty string")
    serialized = json.dumps(profile, ensure_ascii=False)
    if "api-key" in serialized.lower() or "api_key" in serialized.lower():
        raise RuntimeFingerprintError("runtime profile must not contain API key fields")
    return profile


def build_execution_fingerprint(
        runner_path: Path,
        profile_path: Path = DEFAULT_PROFILE,
        application_config_path: Path = DEFAULT_APPLICATION_CONFIG,
        extra: dict[str, Any] | None = None,
        dependency_paths: list[Path] | None = None) -> dict[str, Any]:
    """Build deterministic provenance without environment secrets or timestamps."""
    profile = load_runtime_profile(profile_path)
    fingerprint: dict[str, Any] = {
        "fingerprint_version": FINGERPRINT_VERSION,
        "runner": file_descriptor(runner_path),
        "evaluation_contract": file_descriptor(SCRIPT_DIR / "evaluation_contract.py"),
        "dependencies": [file_descriptor(path) for path in (dependency_paths or [])],
        "runtime_profile": {
            **file_descriptor(profile_path),
            "name": profile["name"],
            "configured_models": profile["configured_models"],
        },
        "application_config": file_descriptor(application_config_path),
        "git": git_metadata(),
        "python": {
            "implementation": platform.python_implementation(),
            "version": platform.python_version(),
        },
    }
    if extra:
        fingerprint["extra"] = extra
    fingerprint["sha256"] = canonical_hash(fingerprint)
    return fingerprint
