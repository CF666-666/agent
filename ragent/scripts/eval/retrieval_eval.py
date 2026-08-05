#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
检索指标 Runner(Phase 8, 8.2)

通过项目 SSE 接口 /rag/v3/chat 获取 references(检索结果按序返回,先于 LLM 生成),
对评测集计算 Hit Rate@K / MRR@K,输出汇总报告(JSON + Markdown)。

检索结果在 LLM 生成前即由 references 事件发出,因此流式读取到 references 即可断开,
无需等待完整回答,评测速度快。

用法:
    python retrieval_eval.py [--base-url http://localhost:9090/api/ragent]
        [--username admin] [--password admin]
        [--dataset datasets/industrial_eval.jsonl] [--out report/retrieval_report.json]
"""
import argparse
import hashlib
import json
import re
import sys
from difflib import SequenceMatcher
from pathlib import Path
from collections import defaultdict

import requests
import urllib.parse

TOPK = (1, 3, 5)
REPORT_SCHEMA_VERSION = 2

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


def login(base: str, username: str, password: str) -> str:
    r = requests.post(f"{base}/auth/login", json={"username": username, "password": password}, timeout=20)
    r.raise_for_status()
    data = r.json()
    if data.get("code") != "0":
        raise RuntimeError(f"登录失败: {data}")
    return data["data"]["token"]


def stream_refs(base: str, token: str, question: str,
                enable_rewrite: bool = True,
                enable_image: bool = True,
                enable_hypergraph: bool = True,
                enable_fusion: bool = True,
                timeout: int = 60):
    """
    流式调用 SSE,读取到 references 事件(检索结果)即断开。
    返回 (references, ok) ;ok=False 表示未获取到检索结果(弱检索/未检索到)。
    """
    url = (f"{base}/rag/v3/chat?question=" + urllib.parse.quote(question)
           + f"&enableRewrite={str(enable_rewrite).lower()}"
           + f"&enableImage={str(enable_image).lower()}"
           + f"&enableHyperGraph={str(enable_hypergraph).lower()}"
           + f"&enableFusion={str(enable_fusion).lower()}")
    references = []
    current_event = ""
    try:
        with requests.get(url, headers={"Authorization": token}, timeout=timeout, stream=True) as resp:
            resp.raise_for_status()
            for raw in resp.iter_lines(decode_unicode=True):
                if not raw:
                    continue
                if raw.startswith("event:"):
                    current_event = raw[6:].strip()
                    continue
                if not raw.startswith("data:"):
                    continue
                payload = raw[5:].strip()
                if payload == "[DONE]":
                    break
                if current_event == "reject":
                    return references, False
                if current_event == "references":
                    try:
                        refs = json.loads(payload)
                        if isinstance(refs, list):
                            references.extend(refs)
                    except Exception:
                        pass
                    break  # 检索结果已拿到,断开,无需等 LLM 生成
    except requests.RequestException as e:
        print(f"    [warn] 请求异常: {e}", file=sys.stderr)
    return references, bool(references)


REMOVE_CHARS = " \t\r\n\u3000，。！？、；：\"'（）[]{}《》.,;:!?<>/\\|_—-"


def normalize(s: str) -> str:
    return str(s).translate(str.maketrans("", "", REMOVE_CHARS))


def is_hit(golden: str, snippet: str) -> bool:
    g = normalize(golden)
    s = normalize(snippet)
    if not g or not s:
        return False
    if g in s:
        return True
    return SequenceMatcher(None, g, s).ratio() > 0.6


def expected_reference_types(expected_channels):
    mapping = {
        "IMAGE_SEMANTIC": "IMAGE",
        "HYPERGRAPH": "HYPERGRAPH",
        "VECTOR_GLOBAL": "TEXT",
        "INTENT_DIRECTED": "TEXT",
        "KEYWORD_ES": "TEXT",
        "HYBRID": "TEXT",
    }
    return {mapping[channel] for channel in expected_channels if channel in mapping}


def evaluation_case_id(item: dict) -> str:
    """Return a stable identity for one labeled evaluation case."""
    payload = json.dumps(item, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def source_id_hit(references, golden_source_ids):
    golden_ids = {str(value) for value in (golden_source_ids or [])}
    if not golden_ids:
        return False
    for reference in references:
        extra = reference.get("extra") or {}
        if str(extra.get("sourceId")) in golden_ids:
            return True
    return False


def metrics(references, golden, expected_channels=None, golden_source_ids=None):
    hits = {k: any(is_hit(golden, r.get("snippet") or "") for r in references[:k]) for k in TOPK}
    mrr = 0.0
    for i, r in enumerate(references):
        if is_hit(golden, r.get("snippet") or ""):
            mrr = 1.0 / (i + 1)
            break
    expected_types = expected_reference_types(expected_channels or [])
    channel_hit = any(r.get("type") in expected_types for r in references)
    return hits, mrr, channel_hit, source_id_hit(references, golden_source_ids)


def load_dataset(path: Path):
    items = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                items.append(json.loads(line))
    return items


def select_evaluation_items(items, scenes: str, offset: int, limit: int):
    """Select a deterministic, non-empty evaluation slice after scene filtering."""
    if offset < 0:
        raise ValueError("evaluation offset must be greater than or equal to zero")
    if limit < 0:
        raise ValueError("evaluation limit must be greater than or equal to zero")

    selected = items
    if scenes.strip():
        selected_scenes = {scene.strip() for scene in scenes.split(",") if scene.strip()}
        selected = [item for item in selected if item.get("scene") in selected_scenes]
    selected = selected[offset:]
    if limit > 0:
        selected = selected[:limit]
    if not selected:
        raise ValueError("evaluation dataset is empty after scene, offset and limit filters")
    return selected


def describe_dataset(path: Path) -> dict:
    """Return stable provenance for an evaluation dataset."""
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    project_root = Path(__file__).resolve().parents[2]
    try:
        display_path = path.resolve().relative_to(project_root).as_posix()
    except ValueError:
        display_path = path.as_posix()
    return {
        "path": display_path,
        "sha256": digest,
    }


def retrieval_options(enable_rewrite: bool,
                      enable_image: bool,
                      enable_hypergraph: bool,
                      enable_fusion: bool,
                      label: str) -> dict:
    return {
        "label": label,
        "enableRewrite": enable_rewrite,
        "enableImage": enable_image,
        "enableHyperGraph": enable_hypergraph,
        "enableFusion": enable_fusion,
    }


def main():
    parser = argparse.ArgumentParser(description="检索指标 Runner")
    parser.add_argument("--base-url", default="http://localhost:9090/api/ragent")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin")
    parser.add_argument("--dataset", type=Path, default=Path(__file__).parent / "datasets/industrial_eval.jsonl")
    parser.add_argument("--scenes", default="",
                        help="仅评测指定场景，逗号分隔；例如 fact,colloquial")
    parser.add_argument("--out", type=Path, default=Path(__file__).parent / "report/retrieval_report.json")
    parser.add_argument("--label", default="",
                        help="本次实验配置标签，例如 A-text-baseline 或 D-full-chain")
    parser.add_argument("--limit", type=int, default=0, help="仅评测前 N 条(调试用,0=全部)")
    parser.add_argument("--offset", type=int, default=0,
                        help="Start after N scene-filtered samples; use with --limit for batched runs.")
    parser.add_argument("--enable-rewrite", action="store_true", default=True,
                        help="是否启用查询重写(默认启用;配合 A/B 对比关闭)")
    parser.add_argument("--disable-rewrite", action="store_true", default=False,
                        help="禁用查询重写(评测基线用)")
    parser.add_argument("--disable-image", action="store_true", default=False,
                        help="关闭图像语义通道")
    parser.add_argument("--disable-hypergraph", action="store_true", default=False,
                        help="关闭超图通道")
    parser.add_argument("--disable-fusion", action="store_true", default=False,
                        help="关闭多源融合")
    parser.add_argument("--request-timeout", type=int, default=10,
                        help="单条 SSE 检索请求超时秒数(无 references 时快速判定 no_retrieval)")
    args = parser.parse_args()
    enable_rewrite = not args.disable_rewrite
    enable_image = not args.disable_image
    enable_hypergraph = not args.disable_hypergraph
    enable_fusion = not args.disable_fusion

    print(f"[retrieval_eval] 登录 {args.base_url} ...")
    token = login(args.base_url, args.username, args.password)
    items = load_dataset(args.dataset)
    items = select_evaluation_items(items, args.scenes, args.offset, args.limit)
    mode = "rewrite-on" if enable_rewrite else "rewrite-off"
    print(f"[retrieval_eval] 评测集 {len(items)} 条 | 查询重写: {'开启' if enable_rewrite else '关闭'}")

    results = []
    for idx, it in enumerate(items, 1):
        query, golden = it["query"], it["golden_answer"]
        refs, ok = stream_refs(args.base_url, token, query,
                               enable_rewrite, enable_image,
                               enable_hypergraph, enable_fusion,
                               timeout=args.request_timeout)
        hits, mrr, channel_hit, source_hit = metrics(
            refs, golden, it.get("expected_channels", []), it.get("golden_source_ids", [])) if ok else (
                {k: False for k in TOPK}, 0.0, False, False)
        results.append({
            "case_id": evaluation_case_id(it),
            "query": query, "scene": it.get("scene", ""), "ok": ok,
            "num_refs": len(refs), "hit": hits, "mrr": mrr,
            "channel_hit": channel_hit,
            "source_id_hit": source_hit,
        })
        detail = f"{idx}/{len(items)} [{it.get('scene','')}] hit@1={hits[1]} mrr={mrr:.3f} refs={len(refs)}"
        print("  " + detail)
        if not ok:
            print(f"    !!! 未检索到有效内容: {query}")

    # ---- 汇总 ----
    total = len(results)
    hit_agg = {k: sum(1 for r in results if r["hit"][k]) / total for k in TOPK}
    mrr_agg = sum(r["mrr"] for r in results) / total
    no_retrieval = sum(1 for r in results if not r["ok"])
    by_scene = defaultdict(list)
    for r in results:
        by_scene[r["scene"]].append(r)

    channel_hit_rate = sum(1 for r in results if r["channel_hit"]) / total if total else 0.0
    source_id_hit_rate = sum(1 for r in results if r["source_id_hit"]) / total if total else 0.0
    summary = {
        "total": total, "no_retrieval": no_retrieval,
        "hit_rate": {f"@{k}": round(v, 4) for k, v in hit_agg.items()},
        "mrr": round(mrr_agg, 4),
        "expected_channel_hit_rate": round(channel_hit_rate, 4),
        "source_id_hit_rate": round(source_id_hit_rate, 4),
        "by_scene": {
            scene: {
                "count": len(lst),
                "hit_rate": {f"@{k}": round(sum(1 for r in lst if r["hit"][k]) / len(lst), 4) for k in TOPK},
                "mrr": round(sum(r["mrr"] for r in lst) / len(lst), 4),
                "expected_channel_hit_rate": round(
                    sum(1 for r in lst if r["channel_hit"]) / len(lst), 4),
                "source_id_hit_rate": round(
                    sum(1 for r in lst if r["source_id_hit"]) / len(lst), 4),
            }
            for scene, lst in sorted(by_scene.items())
        },
    }
    if args.out.name == "retrieval_report.json":
        args.out = args.out.with_name(f"retrieval_{mode}.json")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "schema_version": REPORT_SCHEMA_VERSION,
        "mode": mode,
        "dataset": describe_dataset(args.dataset),
        "retrieval_options": retrieval_options(
            enable_rewrite, enable_image, enable_hypergraph, enable_fusion, args.label),
        "evaluation_slice": {
            "scenes": args.scenes,
            "offset": args.offset,
            "limit": args.limit,
            "count": len(results),
        },
        "summary": summary,
        "results": results,
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    print("\n================ 检索指标汇总 ================")
    print(f"模式: {'查询重写开启' if enable_rewrite else '查询重写关闭'}")
    print(f"样本数: {total} | 未检索到: {no_retrieval}")
    for k in TOPK:
        print(f"Hit Rate@{k}: {summary['hit_rate'][f'@{k}']:.2%}")
    print(f"MRR: {mrr_agg:.4f}")
    print(f"报告输出: {args.out}")


if __name__ == "__main__":
    main()
