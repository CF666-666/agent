#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RAGAS 生成质量评测(Phase 8, 8.3)

流程:
1. 登录项目,对评测集每条 query 调用 SSE /rag/v3/chat 获取完整回答(answer)与检索上下文(contexts);
2. 构造 RAGAS 评测样本(user_input / response / retrieved_contexts / reference);
3. 使用 ragas 计算 faithfulness / answer_relevancy / context_precision / context_recall;
4. 输出汇总报告(report/ragas_report.json + 终端汇总)。

依赖:ragas、langchain-openai、requests(已装于 scripts/eval/.venv)
用法:
    python ragas_eval.py [--dataset datasets/industrial_eval.jsonl] [--limit 12] [--out report/ragas_report.json]
"""
import argparse
import json
import os
import sys
import urllib.parse
from pathlib import Path

import requests
from langchain_openai import ChatOpenAI, OpenAIEmbeddings

from ragas import EvaluationDataset, SingleTurnSample, evaluate
from ragas.embeddings import LangchainEmbeddingsWrapper
from ragas.llms import LangchainLLMWrapper
from ragas.metrics import (
    answer_relevancy,
    context_precision,
    context_recall,
    faithfulness,
)

BAILIAN_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
SILICON_BASE = "https://api.siliconflow.cn/v1"


def login(base: str, username: str, password: str) -> str:
    r = requests.post(f"{base}/auth/login", json={"username": username, "password": password}, timeout=20)
    r.raise_for_status()
    data = r.json()
    if data.get("code") != "0":
        raise RuntimeError(f"登录失败: {data}")
    return data["data"]["token"]


def stream_answer(base: str, token: str, question: str, timeout: int = 180):
    """调用 SSE 获取完整回答与检索上下文。返回 (answer, [snippets])"""
    url = f"{base}/rag/v3/chat?question=" + urllib.parse.quote(question)
    refs, answer_parts = [], []
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
                if current_event == "references":
                    try:
                        arr = json.loads(payload)
                        if isinstance(arr, list):
                            refs = arr
                    except Exception:
                        pass
                elif current_event == "message":
                    try:
                        obj = json.loads(payload)
                        delta = obj.get("delta", "")
                        if delta:
                            answer_parts.append(delta)
                    except Exception:
                        pass
    except requests.RequestException as e:
        print(f"    [warn] 请求异常: {e}", file=sys.stderr)
    contexts = [r.get("snippet", "") for r in refs if r.get("snippet")]
    return "".join(answer_parts).strip(), contexts


def load_dataset(path: Path):
    items = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                items.append(json.loads(line))
    return items


def main():
    parser = argparse.ArgumentParser(description="RAGAS 生成质量评测")
    parser.add_argument("--base-url", default="http://localhost:9090/api/ragent")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin")
    parser.add_argument("--dataset", type=Path, default=Path(__file__).parent / "datasets/industrial_eval.jsonl")
    parser.add_argument("--limit", type=int, default=12, help="评测样本数(默认 12)")
    parser.add_argument("--eval-model", default="deepseek-ai/DeepSeek-V3.2",
                        help="RAGAS 打分 LLM 模型(默认 SiliconFlow DeepSeek-V3.2)")
    parser.add_argument("--eval-provider", choices=["bailian", "siliconflow"], default="siliconflow",
                        help="打分服务商(默认 siliconflow;bailian 需百炼余额)")
    parser.add_argument("--out", type=Path, default=Path(__file__).parent / "report/ragas_report.json")
    args = parser.parse_args()

    if args.eval_provider == "bailian":
        api_key = os.environ.get("BAILIAN_API_KEY", "").strip()
        llm_base, emb_model = BAILIAN_BASE, "text-embedding-v3"
    else:
        api_key = os.environ.get("SILICONFLOW_API_KEY", "").strip()
        llm_base, emb_model = SILICON_BASE, "BAAI/bge-m3"
    if not api_key:
        print(f"[error] 未设置 {args.eval_provider} 对应的 API Key 环境变量", file=sys.stderr)
        sys.exit(1)

    print(f"[ragas_eval] 登录 {args.base_url} ...")
    token = login(args.base_url, args.username, args.password)
    items = load_dataset(args.dataset)
    if args.limit > 0:
        items = items[: args.limit]
    print(f"[ragas_eval] 评测 {len(items)} 条,打分服务商 {args.eval_provider} 模型 {args.eval_model}")

    # ---- 1. 采集回答与上下文 ----
    samples = []
    for idx, it in enumerate(items, 1):
        query = it["query"]
        print(f"  [{idx}/{len(items)}] 请求回答: {query[:30]}...")
        answer, contexts = stream_answer(args.base_url, token, query)
        if not answer:
            print(f"    [warn] 未获取到回答: {query}")
            continue
        samples.append(SingleTurnSample(
            user_input=query,
            response=answer,
            retrieved_contexts=contexts,
            reference=it.get("golden_answer", ""),
        ))
        print(f"    -> 回答长度 {len(answer)} | 上下文 {len(contexts)} 段")

    if not samples:
        print("[error] 无有效样本", file=sys.stderr)
        sys.exit(1)

    # ---- 2. RAGAS 评测 ----
    print(f"[ragas_eval] 开始 RAGAS 评测({len(samples)} 样本)...")
    eval_llm = LangchainLLMWrapper(ChatOpenAI(
        model=args.eval_model, api_key=api_key, base_url=llm_base, temperature=0,
    ))
    eval_emb = LangchainEmbeddingsWrapper(OpenAIEmbeddings(
        model=emb_model, api_key=api_key, base_url=llm_base,
    ))
    metrics = [faithfulness, answer_relevancy, context_precision, context_recall]
    result = evaluate(
        EvaluationDataset(samples=samples),
        metrics=metrics,
        llm=eval_llm,
        embeddings=eval_emb,
    )

    # ---- 3. 汇总输出 ----
    df = result.to_pandas()
    summary = {}
    for m in metrics:
        mean = float(df[m.name].mean(skipna=True)) if df[m.name].notna().any() else 0.0
        summary[m.name] = round(mean, 4)
    summary["samples"] = len(samples)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump({"summary": summary, "per_sample": df.to_dict(orient="records")},
                  f, ensure_ascii=False, indent=2)

    print("\n================ RAGAS 指标汇总 ================")
    for k, v in summary.items():
        print(f"{k}: {v}")
    print(f"报告输出: {args.out}")


if __name__ == "__main__":
    main()
