#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R4-C 校验:golden image ID ↔ 原始图片 ↔ SSE reference 一致。

三件套一致性:
1. 评测集 golden_image_paths(40 个唯一路径) 全部在 Milvus industrial_images
   的 metadata.imagePath 中;
2. 每个路径对应本地文件存在且字节数>0;
3. 通过 SSE reference 的 /files/{encoded_path} URL 可访问(HTTP 200 + 正确字节数)。

结果写 JSON 文件避免 GBK 控制台编码问题。
"""
import json
import urllib.parse
import urllib.request
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[2]
DATASET = ROOT / "scripts/eval/datasets/industrial_image_r5_approved.jsonl"
IMAGES_DIR = ROOT / "bootstrap/data/images"
BASE = "http://localhost:9090/api/ragent"
MILVUS = "http://localhost:19530"
COLLECTION = "industrial_images"


def login() -> str:
    r = requests.post(f"{BASE}/auth/login",
                      json={"username": "admin", "password": "admin"}, timeout=20)
    r.raise_for_status()
    return r.json()["data"]["token"]


def load_golden_paths() -> list[str]:
    rows = [json.loads(x) for x in DATASET.read_text(encoding="utf-8").splitlines() if x.strip()]
    seen = {}
    for r in rows:
        for p in r.get("golden_image_paths", []):
            seen[p] = True
    return sorted(seen)


def milvus_image_paths() -> set[str]:
    r = requests.post(f"{MILVUS}/v2/vectordb/entities/query",
                      json={"collectionName": COLLECTION,
                            "outputFields": ["metadata"], "limit": 2000}, timeout=60)
    r.raise_for_status()
    body = r.json()
    paths = set()
    for row in body.get("data") or []:
        meta = row.get("metadata") or {}
        # Milvus 可能把 metadata 存成 JSON 字符串
        if isinstance(meta, str):
            meta = json.loads(meta) if meta.strip() else {}
        p = meta.get("imagePath")
        if p:
            paths.add(p)
    return paths


def check_file_and_url(path: str) -> dict:
    result = {"path": path}
    local = IMAGES_DIR / path
    result["file_exists"] = local.exists()
    result["file_bytes"] = local.stat().st_size if local.exists() else 0

    # 构造 SSE reference 的 URL:/files/{encoded_path}(分段编码)
    encoded = "/".join(urllib.parse.quote(seg) for seg in path.split("/"))
    url = f"{BASE}/files/{encoded}"
    result["url"] = url
    try:
        with urllib.request.urlopen(url, timeout=15) as resp:
            body = resp.read()
            result["http_status"] = resp.status
            result["content_type"] = resp.headers.get("Content-Type")
            result["url_bytes"] = len(body)
    except Exception as exc:
        result["http_status"] = None
        result["url_bytes"] = 0
        result["error"] = str(exc)
    result["bytes_match"] = (result["file_bytes"] == result["url_bytes"]
                             and result["file_bytes"] > 0)
    return result


def main() -> int:
    golden = load_golden_paths()
    milvus = milvus_image_paths()

    missing_in_milvus = [p for p in golden if p not in milvus]
    extra_in_milvus = [p for p in milvus if p not in golden]

    checks = [check_file_and_url(p) for p in golden]

    report = {
        "golden_path_count": len(golden),
        "milvus_path_count": len(milvus),
        "missing_in_milvus": missing_in_milvus,
        "extra_in_milvus": extra_in_milvus,
        "file_and_url_ok": sum(1 for c in checks if c["bytes_match"]),
        "file_and_url_failed": [c for c in checks if not c["bytes_match"]],
        "details": checks,
    }
    out = Path(__file__).resolve().parent / "report/r4c_20260815_ref_check.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"golden={len(golden)} milvus={len(milvus)} "
          f"missing_in_milvus={len(missing_in_milvus)} "
          f"file_url_ok={report['file_and_url_ok']}/{len(golden)}")
    return 0 if not missing_in_milvus and report["file_and_url_ok"] == len(golden) else 1


if __name__ == "__main__":
    raise SystemExit(main())
