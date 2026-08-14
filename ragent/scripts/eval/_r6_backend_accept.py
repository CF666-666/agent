#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R6 backend acceptance probe: exercise the ingestion pipeline API for the
five end-to-end scenarios (backend portion). Writes JSON evidence."""
import json
import sys
import time
from pathlib import Path

import requests

SCRIPT_DIR = Path(__file__).resolve().parent

BASE = "http://localhost:9090/api/ragent"


def login():
    r = requests.post(f"{BASE}/auth/login", json={"username": "admin", "password": "admin"}, timeout=20)
    r.raise_for_status()
    return r.json()["data"]["token"]


def call(method, path, token, payload=None):
    headers = {"Authorization": token, "Content-Type": "application/json"}
    url = f"{BASE}{path}"
    if method == "GET":
        resp = requests.get(url, headers=headers, timeout=30)
    elif method == "POST":
        resp = requests.post(url, headers=headers, json=payload, timeout=30)
    elif method == "PUT":
        resp = requests.put(url, headers=headers, json=payload, timeout=30)
    elif method == "DELETE":
        resp = requests.delete(url, headers=headers, timeout=30)
    else:
        raise ValueError(method)
    try:
        body = resp.json()
    except Exception:
        body = {"raw": resp.text[:500]}
    return resp.status_code, body


def node(node_id, node_type, settings=None, condition=None):
    return {
        "nodeId": node_id, "nodeType": node_type,
        "settings": settings or {}, "condition": condition,
        "executionPolicy": None, "nextNodeId": None,
    }


def main() -> int:
    token = login()
    evidence = {"scenarios": {}}

    # ---- Scenario 1: create conditional-branch pipeline ----
    s1_nodes = [
        node("node_1", "fetcher", {"__position": {"x": 0, "y": 0}}),
        node("node_2", "parser", {"__position": {"x": 220, "y": 0}}),
        node("node_3", "multimodal_parse", {"__position": {"x": 220, "y": 120}}),
        node("node_4", "chunker", {"__position": {"x": 440, "y": 0}}),
    ]
    # 用时间戳后缀避免重名（pipeline 删除受 task FK 约束，可能无法清理）
    import time as _time
    pipeline_name = f"R6-Accept-Branch-{int(_time.time())}"
    s1_edges = [
        {"edgeId": None, "fromNodeId": "node_1", "toNodeId": "node_2",
         "condition": None, "priority": 0, "defaultEdge": True},
        {"edgeId": None, "fromNodeId": "node_2", "toNodeId": "node_4",
         "condition": {"field": "mimeType", "operator": "eq", "value": "application/pdf"},
         "priority": 10, "defaultEdge": False},
        {"edgeId": None, "fromNodeId": "node_2", "toNodeId": "node_3",
         "condition": {"field": "mimeType", "operator": "eq", "value": "image/*"},
         "priority": 20, "defaultEdge": False},
        {"edgeId": None, "fromNodeId": "node_3", "toNodeId": "node_4",
         "condition": None, "priority": 0, "defaultEdge": True},
    ]
    status, body = call("POST", "/ingestion/pipelines", token,
                        {"name": pipeline_name, "description": "conditional branch",
                         "nodes": s1_nodes, "edges": s1_edges})
    evidence["scenarios"]["s1_create"] = {"status": status, "ok": status == 200,
                                          "code": body.get("code"), "message": body.get("message")}
    pipeline_id = None
    if status == 200 and body.get("data"):
        pipeline_id = body["data"].get("id")
        data = body["data"]
        evidence["scenarios"]["s1_create"]["data"] = {
            "id": data.get("id"),
            "name": data.get("name"),
            "node_count": len(data.get("nodes") or []),
            "edge_count": len(data.get("edges") or []),
        }
    else:
        evidence["scenarios"]["s1_create"]["body"] = body

    # ---- Scenario 2: edge condition + priority round-trip on update ----
    if pipeline_id:
        upd_edges = [
            {"edgeId": None, "fromNodeId": "node_1", "toNodeId": "node_2",
             "condition": None, "priority": 0, "defaultEdge": True},
            {"edgeId": None, "fromNodeId": "node_2", "toNodeId": "node_4",
             "condition": {"field": "mimeType", "operator": "eq", "value": "application/pdf"},
             "priority": 5, "defaultEdge": False},
            {"edgeId": None, "fromNodeId": "node_2", "toNodeId": "node_3",
             "condition": {"field": "mimeType", "operator": "eq", "value": "image/*"},
             "priority": 50, "defaultEdge": False},
            {"edgeId": None, "fromNodeId": "node_3", "toNodeId": "node_4",
             "condition": None, "priority": 0, "defaultEdge": True},
        ]
        status, body = call("PUT", f"/ingestion/pipelines/{pipeline_id}", token,
                            {"name": "R6-Accept-Branch", "description": "conditional branch",
                             "nodes": s1_nodes, "edges": upd_edges})
        evidence["scenarios"]["s2_update"] = {"status": status, "ok": status == 200}
        # read back
        status, body = call("GET", f"/ingestion/pipelines/{pipeline_id}", token)
        if status == 200 and body.get("data"):
            data = body["data"]
            edges = data.get("edges") or []
            edge_by_from = {}
            for e in edges:
                edge_by_from.setdefault(e.get("fromNodeId"), []).append(e)
            parser_edges = sorted(edge_by_from.get("node_2", []),
                                  key=lambda e: (e.get("defaultEdge"), -(e.get("priority") or 0)))
            evidence["scenarios"]["s2_update"]["roundtrip"] = {
                "edge_count": len(edges),
                "parser_conditional_priorities":
                    [{"to": e.get("toNodeId"), "priority": e.get("priority"),
                      "default": e.get("defaultEdge"),
                      "condition": e.get("condition")} for e in parser_edges],
            }

        # ---- Scenario 3: position + settings + edges fully restored ----
        status, body = call("GET", f"/ingestion/pipelines/{pipeline_id}", token)
        data = body.get("data") or {}
        nodes = data.get("nodes") or []
        positions = []
        for n in nodes:
            settings = n.get("settings") or {}
            positions.append({"nodeId": n.get("nodeId"),
                              "position": settings.get("__position")})
        evidence["scenarios"]["s3_restore"] = {
            "status": status, "ok": status == 200,
            "node_count": len(nodes), "edge_count": len(data.get("edges") or []),
            "positions": positions,
        }

    # ---- Scenario 4: cycle rejected by backend ----
    # Ragent 的全局异常处理器把 ClientException 包装成 Result(code="A000001")
    # 而非 HTTP 400。判定按"业务码识别失败"而不是按 HTTP 状态码。
    cycle_nodes = [
        node("node_1", "fetcher"), node("node_2", "parser"), node("node_3", "chunker"),
    ]
    cycle_edges = [
        {"edgeId": None, "fromNodeId": "node_1", "toNodeId": "node_2",
         "condition": None, "priority": 0, "defaultEdge": True},
        {"edgeId": None, "fromNodeId": "node_2", "toNodeId": "node_3",
         "condition": None, "priority": 0, "defaultEdge": True},
        {"edgeId": None, "fromNodeId": "node_3", "toNodeId": "node_1",
         "condition": None, "priority": 0, "defaultEdge": True},
    ]
    status, body = call("POST", "/ingestion/pipelines", token,
                        {"name": "R6-Accept-Cycle", "description": "cycle",
                         "nodes": cycle_nodes, "edges": cycle_edges})
    cycle_rejected = (status == 200 and body.get("code") == "A000001"
                      and "cycle" in (body.get("message") or "").lower())
    evidence["scenarios"]["s4_cycle"] = {
        "status": status, "ok": cycle_rejected,
        "code": body.get("code"), "message": body.get("message"),
    }
    # cycle 测试用 pipeline 立刻删除（与 s1_accept_branch 隔离）
    if status == 200 and body.get("data"):
        call("DELETE", f"/ingestion/pipelines/{body['data']['id']}", token)

    # ---- Scenario 5: run task -> topology status + node logs ----
    status, body = call("POST", "/ingestion/tasks", token, {
        "pipelineId": pipeline_id,
        "source": {"type": "file", "location": "demo.pdf", "fileName": "demo.pdf"},
        "metadata": {"eval": "r6-accept"},
        "vectorSpaceId": {},
    })
    task_id = None
    if status == 200 and body.get("data"):
        task_id = body["data"].get("taskId") or body["data"].get("id")
    evidence["scenarios"]["s5_task_create"] = {
        "status": status, "taskId": task_id, "body": body if status != 200 else None}

    if task_id:
        time.sleep(2)
        status, body = call("GET", f"/ingestion/tasks/{task_id}/nodes", token)
        node_rows = body.get("data") or []
        evidence["scenarios"]["s5_task_topology"] = {
            "status": status,
            "node_statuses": [{"nodeId": n.get("nodeId"), "nodeType": n.get("nodeType"),
                               "status": n.get("status"), "durationMs": n.get("durationMs"),
                               "message": (n.get("message") or "")[:60]} for n in node_rows],
        }
        status, body = call("GET", f"/ingestion/tasks/{task_id}", token)
        task = body.get("data") or {}
        evidence["scenarios"]["s5_task_detail"] = {
            "status": status, "taskStatus": task.get("status"),
            "chunkCount": task.get("chunkCount"),
            "log_count": len(task.get("logs") or []),
            "error": task.get("errorMessage"),
        }

    # R6 accept pipeline 保留（用于浏览器画布验收截图；不被 cleanup）

    # 落盘路径：以本脚本所在目录为基准，避免 cwd 依赖（审查 A1）
    out_dir = SCRIPT_DIR / "report" / "r6_accept_20260814"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / "_backend_evidence.json"
    out_path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
