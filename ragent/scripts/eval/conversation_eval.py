#!/usr/bin/env python3
"""Run source-grounded multi-turn retrieval evaluation through real server memory."""

import argparse
import json
import re
import subprocess
import time
import urllib.parse
from pathlib import Path
from typing import Callable

import requests

from evaluation_contract import load_jsonl_dataset
from retrieval_eval import (
    describe_dataset,
    execution_status,
    login,
    metrics,
)
from runtime_fingerprint import (
    DEFAULT_APPLICATION_CONFIG,
    DEFAULT_PROFILE,
    build_execution_fingerprint,
)

REPORT_SCHEMA_VERSION = 1
IMAGE_ID_PATTERN = re.compile(r"^sha256:[0-9a-f]{64}$")


def running_container_image(container_name: str) -> str:
    try:
        image_id = subprocess.check_output(
            ["docker", "inspect", "--format", "{{.Image}}", container_name],
            text=True, stderr=subprocess.STDOUT).strip()
    except (OSError, subprocess.CalledProcessError) as error:
        raise ValueError(f"cannot inspect backend container {container_name}") from error
    if not IMAGE_ID_PATTERN.fullmatch(image_id):
        raise ValueError(f"backend container returned non-immutable image ID: {image_id}")
    return image_id


def elapsed_millis(started_at: float) -> int:
    return round((time.monotonic() - started_at) * 1000)


def stream_chat_full(base: str, token: str, question: str,
                     conversation_id: str | None = None,
                     enable_rewrite: bool = True,
                     enable_image: bool = False,
                     enable_hypergraph: bool = False,
                     enable_fusion: bool = True,
                     timeout: int = 60) -> dict:
    params = {
        "question": question,
        "enableRewrite": str(enable_rewrite).lower(),
        "enableImage": str(enable_image).lower(),
        "enableHyperGraph": str(enable_hypergraph).lower(),
        "enableFusion": str(enable_fusion).lower(),
        "retrievalOnly": "false",
    }
    if conversation_id:
        params["conversationId"] = conversation_id
    url = f"{base}/rag/v3/chat?{urllib.parse.urlencode(params)}"
    started_at = time.monotonic()
    current_event = ""
    answer_parts: list[str] = []
    references: list[dict] = []
    execution = None
    actual_conversation_id = None
    completed = False
    status = "missing_retrieval_status"
    try:
        with requests.get(url, headers={"Authorization": token}, timeout=timeout, stream=True) as response:
            response.raise_for_status()
            for raw in response.iter_lines(decode_unicode=True):
                if not raw:
                    continue
                if raw.startswith("event:"):
                    current_event = raw[6:].strip()
                    continue
                if not raw.startswith("data:"):
                    continue
                payload = raw[5:].strip()
                if current_event == "meta":
                    meta = json.loads(payload)
                    actual_conversation_id = meta.get("conversationId")
                elif current_event == "message":
                    message = json.loads(payload)
                    if message.get("type") == "response":
                        answer_parts.append(str(message.get("delta") or ""))
                elif current_event == "retrieval_status":
                    execution = json.loads(payload)
                elif current_event == "references":
                    parsed = json.loads(payload)
                    if isinstance(parsed, list):
                        references.extend(parsed)
                        status = "received"
                elif current_event == "reject":
                    status = "rejected"
                elif current_event == "finish":
                    completed = True
                elif current_event == "done" or payload == "[DONE]":
                    break
    except requests.Timeout:
        status = "timeout"
    except requests.RequestException:
        status = "request_error"
    status = execution_status(execution, status)
    persisted = False
    if completed and actual_conversation_id and "".join(answer_parts).strip() and status == "received":
        persisted = conversation_turn_persisted(
            base, token, actual_conversation_id, question, "".join(answer_parts), timeout)
    return {
        "conversation_id": actual_conversation_id,
        "answer": "".join(answer_parts),
        "references": references,
        "retrieval_status": status,
        "latency_ms": elapsed_millis(started_at),
        "execution": execution,
        "completed": completed,
        "persisted": persisted,
    }


def conversation_turn_persisted(base: str, token: str, conversation_id: str,
                                question: str, answer: str, timeout: int) -> bool:
    response = requests.get(
        f"{base}/conversations/{urllib.parse.quote(conversation_id)}/messages",
        headers={"Authorization": token}, timeout=timeout)
    response.raise_for_status()
    body = response.json()
    if body.get("code") != "0" or not isinstance(body.get("data"), list):
        return False
    messages = body["data"]
    user_seen = any(message.get("role") == "user" and message.get("content") == question
                    for message in messages if isinstance(message, dict))
    assistant_seen = any(
        message.get("role") == "assistant" and str(message.get("content") or "").strip()
        and str(message.get("content") or "").strip() == answer.strip()
        for message in messages if isinstance(message, dict))
    return user_seen and assistant_seen


def run_conversation_case(item: dict, chat: Callable[[str, str | None], dict]) -> dict:
    conversation_id = None
    turn_results = []
    target_index = item["target_turn_index"]
    for index, turn in enumerate(item["turns"]):
        response = chat(turn["content"], conversation_id)
        actual_id = response.get("conversation_id")
        if not actual_id:
            response["completed"] = False
        if conversation_id is not None and actual_id != conversation_id:
            raise ValueError(
                f"conversation id changed at turn {index}: {conversation_id} -> {actual_id}")
        conversation_id = actual_id or conversation_id
        scored = index == target_index
        turn_results.append({
            "turn_index": index,
            "role": "user",
            "query": turn["content"],
            "answer": response.get("answer", ""),
            "conversation_id": conversation_id,
            "retrieval_status": response.get("retrieval_status"),
            "latency_ms": response.get("latency_ms"),
            "num_refs": len(response.get("references") or []),
            "references": response.get("references") or [],
            "execution": response.get("execution"),
            "completed": bool(response.get("completed")),
            "persisted": bool(response.get("persisted")),
            "scored": scored,
        })
        turn_success = (
            bool(response.get("completed"))
            and bool(str(response.get("answer") or "").strip())
            and response.get("retrieval_status") == "received"
            and bool(response.get("persisted"))
        )
        if not turn_success:
            return {
                "dataset_id": item["id"], "ok": False,
                "status": "target_turn_failed" if scored else "history_turn_failed",
                "failed_turn_index": index, "conversation_id": conversation_id,
                "target_turn_index": target_index,
                "target_query": item["turns"][target_index]["content"],
                "golden_source_ids": item["golden_source_ids"], "turns": turn_results,
                "split": item.get("split"),
                "conversation_type": item.get("conversation_type"),
                "canonical_target_query": item.get("canonical_target_query"),
                "context_notes": item.get("context_notes"),
                "provenance": item.get("provenance"),
            }
        if scored:
            refs = response.get("references") or []
            ok = response.get("retrieval_status") == "received"
            hits, mrr, channel_hit, source_hit = metrics(
                refs, item["golden_answer"], item.get("expected_channels", []),
                item.get("golden_source_ids", [])) if ok else (
                    {1: False, 3: False, 5: False}, 0.0, False, False)
            return {
                "dataset_id": item["id"], "ok": ok,
                "status": response.get("retrieval_status"),
                "conversation_id": conversation_id,
                "target_turn_index": target_index,
                "target_query": turn["content"],
                "golden_source_ids": item["golden_source_ids"],
                "split": item.get("split"),
                "conversation_type": item.get("conversation_type"),
                "canonical_target_query": item.get("canonical_target_query"),
                "context_notes": item.get("context_notes"),
                "provenance": item.get("provenance"),
                "hit": hits, "mrr": mrr, "channel_hit": channel_hit,
                "source_id_hit": source_hit, "turns": turn_results,
            }
    raise ValueError("target turn was not executed")


def summarize(results: list[dict]) -> dict:
    quality = [result for result in results if result["ok"]]
    total = len(results)
    denominator = len(quality)
    return {
        "total": total,
        "quality_sample_count": denominator,
        "excluded_execution_count": total - denominator,
        "hit_rate": {f"@{k}": round(sum(r["hit"][k] for r in quality) / denominator, 4)
                     if denominator else 0.0 for k in (1, 3, 5)},
        "mrr": round(sum(r["mrr"] for r in quality) / denominator, 4) if denominator else 0.0,
        "source_id_hit_rate": round(sum(r["source_id_hit"] for r in quality) / denominator, 4)
        if denominator else 0.0,
        "status_counts": {status: sum(r["status"] == status for r in results)
                          for status in sorted({r["status"] for r in results})},
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:9090/api/ragent")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin")
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--disable-rewrite", action="store_true")
    parser.add_argument("--request-timeout", type=int, default=60)
    parser.add_argument("--warmup-count", type=int, default=1)
    parser.add_argument("--backend-container", default="ragent-backend")
    parser.add_argument("--runtime-profile", type=Path, default=DEFAULT_PROFILE)
    parser.add_argument("--application-config", type=Path, default=DEFAULT_APPLICATION_CONFIG)
    args = parser.parse_args()
    items = load_jsonl_dataset(args.dataset, kind="conversation")
    if args.warmup_count < 0:
        raise ValueError("warmup count must be greater than or equal to zero")
    backend_image = running_container_image(args.backend_container)
    token = login(args.base_url, args.username, args.password)

    def chat(question: str, conversation_id: str | None) -> dict:
        return stream_chat_full(
            args.base_url, token, question, conversation_id,
            enable_rewrite=not args.disable_rewrite, timeout=args.request_timeout)

    warmup_results = [run_conversation_case(items[index % len(items)], chat)
                      for index in range(args.warmup_count)]
    results = [run_conversation_case(item, chat) for item in items]
    report = {
        "schema_version": REPORT_SCHEMA_VERSION,
        "mode": "rewrite-off" if args.disable_rewrite else "rewrite-on",
        "dataset": describe_dataset(args.dataset),
        "retrieval_options": {
            "enableRewrite": not args.disable_rewrite,
            "enableImage": False,
            "enableHyperGraph": False,
            "enableFusion": True,
            "retrievalOnly": False,
        },
        "runtime": {
            "request_timeout_seconds": args.request_timeout,
            "warmup_count": args.warmup_count,
            "backend_container": args.backend_container,
            "backend_image": backend_image,
        },
        "execution_fingerprint": build_execution_fingerprint(
            Path(__file__), args.runtime_profile, args.application_config,
            dependency_paths=[
                Path(__file__).with_name("retrieval_eval.py"),
                Path(__file__).with_name("runtime_fingerprint.py"),
            ]),
        "evaluation_slice": {"count": len(items)},
        "warmup": {
            "requested_count": args.warmup_count,
            "executed_count": len(warmup_results),
            "results": [{"dataset_id": result["dataset_id"], "ok": result["ok"],
                         "status": result["status"]} for result in warmup_results],
        },
        "summary": summarize(results),
        "results": results,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
