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
import json
import re
import sys
from difflib import SequenceMatcher
from pathlib import Path
from collections import defaultdict

import requests
import urllib.parse

TOPK = (1, 3, 5)


def login(base: str, username: str, password: str) -> str:
    r = requests.post(f"{base}/auth/login", json={"username": username, "password": password}, timeout=20)
    r.raise_for_status()
    data = r.json()
    if data.get("code") != "0":
        raise RuntimeError(f"登录失败: {data}")
    return data["data"]["token"]


def stream_refs(base: str, token: str, question: str, enable_rewrite: bool = True, timeout: int = 60):
    """
    流式调用 SSE,读取到 references 事件(检索结果)即断开。
    返回 (references, ok) ;ok=False 表示未获取到检索结果(弱检索/未检索到)。
    """
    url = (f"{base}/rag/v3/chat?question=" + urllib.parse.quote(question)
           + f"&enableRewrite={str(enable_rewrite).lower()}")
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


def normalize(s: str) -> str:
    return re.sub(r"[\s\u3000，。！？、；：""''（）\[\]{}《》\.\,\;\:\!\?\(\)\[\]<>/\\|_\-—-]+", "", str(s))


def is_hit(golden: str, snippet: str) -> bool:
    g = normalize(golden)
    s = normalize(snippet)
    if not g or not s:
        return False
    if g in s:
        return True
    return SequenceMatcher(None, g, s).ratio() > 0.6


def metrics(references, golden):
    hits = {k: any(is_hit(golden, r.get("snippet") or "") for r in references[:k]) for k in TOPK}
    mrr = 0.0
    for i, r in enumerate(references):
        if is_hit(golden, r.get("snippet") or ""):
            mrr = 1.0 / (i + 1)
            break
    return hits, mrr


def load_dataset(path: Path):
    items = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                items.append(json.loads(line))
    return items


def main():
    parser = argparse.ArgumentParser(description="检索指标 Runner")
    parser.add_argument("--base-url", default="http://localhost:9090/api/ragent")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin")
    parser.add_argument("--dataset", type=Path, default=Path(__file__).parent / "datasets/industrial_eval.jsonl")
    parser.add_argument("--out", type=Path, default=Path(__file__).parent / "report/retrieval_report.json")
    parser.add_argument("--limit", type=int, default=0, help="仅评测前 N 条(调试用,0=全部)")
    parser.add_argument("--enable-rewrite", action="store_true", default=True,
                        help="是否启用查询重写(默认启用;配合 A/B 对比关闭)")
    parser.add_argument("--disable-rewrite", action="store_true", default=False,
                        help="禁用查询重写(评测基线用)")
    args = parser.parse_args()
    enable_rewrite = not args.disable_rewrite

    print(f"[retrieval_eval] 登录 {args.base_url} ...")
    token = login(args.base_url, args.username, args.password)
    items = load_dataset(args.dataset)
    if args.limit > 0:
        items = items[: args.limit]
    mode = "rewrite-on" if enable_rewrite else "rewrite-off"
    print(f"[retrieval_eval] 评测集 {len(items)} 条 | 查询重写: {'开启' if enable_rewrite else '关闭'}")

    results = []
    for idx, it in enumerate(items, 1):
        query, golden = it["query"], it["golden_answer"]
        refs, ok = stream_refs(args.base_url, token, query, enable_rewrite)
        hits, mrr = metrics(refs, golden) if ok else ({k: False for k in TOPK}, 0.0)
        results.append({
            "query": query, "scene": it.get("scene", ""), "ok": ok,
            "num_refs": len(refs), "hit": hits, "mrr": mrr,
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

    summary = {
        "total": total, "no_retrieval": no_retrieval,
        "hit_rate": {f"@{k}": round(v, 4) for k, v in hit_agg.items()},
        "mrr": round(mrr_agg, 4),
        "by_scene": {
            scene: {
                "count": len(lst),
                "hit_rate": {f"@{k}": round(sum(1 for r in lst if r["hit"][k]) / len(lst), 4) for k in TOPK},
                "mrr": round(sum(r["mrr"] for r in lst) / len(lst), 4),
            }
            for scene, lst in sorted(by_scene.items())
        },
    }
    if args.out.name.endswith(".json"):
        args.out = args.out.with_name(f"retrieval_{mode}.json")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump({"mode": mode, "summary": summary, "results": results}, f, ensure_ascii=False, indent=2)

    print("\n================ 检索指标汇总 ================")
    print(f"模式: {'查询重写开启' if enable_rewrite else '查询重写关闭'}")
    print(f"样本数: {total} | 未检索到: {no_retrieval}")
    for k in TOPK:
        print(f"Hit Rate@{k}: {summary['hit_rate'][f'@{k}']:.2%}")
    print(f"MRR: {mrr_agg:.4f}")
    print(f"报告输出: {args.out}")


if __name__ == "__main__":
    main()
