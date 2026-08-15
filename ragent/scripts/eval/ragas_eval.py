#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RAGAS 生成质量评测(R5-C 扩展版)

流程:
1. 登录项目,对评测集每条 query 调用 SSE /rag/v3/chat 获取完整回答(answer)与检索上下文(contexts);
2. 构造 RAGAS 评测样本(user_input / response / retrieved_contexts / reference);
3. 使用 ragas 计算 faithfulness / answer_relevancy / context_precision / context_recall;
4. 输出汇总报告(分层指标 + 95% 置信区间 + 失败统计 + 业务标签分布)。

R5-C 新增能力:
- 分层:按 ragas_group(text/noise/image/relation)分组统计,legacy 数据按 scene 映射;
- 固定种子:--seed 控制分层抽样的确定性;
- 断点恢复:采集结果逐条落盘 checkpoint,--resume 跳过已采集/已失败样本;
- 失败重试:采集失败重试 --retries 次,打分失败整体重试一次;
- 失败统计:报告区分 scored / failed_collect / failed_score。

纯函数逻辑位于 ragas_eval_core.py,可离线单测;本文件仅保留依赖 ragas/langchain
的采集与打分流程。

依赖:ragas、langchain-openai、requests(已装于 scripts/eval/.venv)
用法:
    python ragas_eval.py [--dataset datasets/industrial_eval_v2.jsonl] [--per-group 15]
    python ragas_eval.py --resume --out report/ragas_report.json
"""
import argparse
import json
import math
import os
import sys
import urllib.parse
from collections import Counter, OrderedDict
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
from runtime_fingerprint import (
    DEFAULT_APPLICATION_CONFIG,
    DEFAULT_PROFILE,
    build_execution_fingerprint,
)
from ragas_eval_core import (
    RAGAS_GROUP_ORDER,
    INTERNAL_METRICS,
    REPORT_SCHEMA_VERSION,
    append_checkpoint,
    case_id,
    checkpoint_path,
    load_checkpoint,
    load_dataset,
    metric_summary,
    ragas_group_of,
    select_items,
)

SILICON_BASE = "https://api.siliconflow.cn/v1"
BAILIAN_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"

ALL_METRICS = (faithfulness, answer_relevancy, context_precision, context_recall)


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


def collect_records(items: list[dict], checkpoint_file: Path, base: str, token: str,
                    retries: int, resume: bool, retry_failed: bool) -> tuple[list[dict], int]:
    """逐条采集回答,支持断点恢复与失败重试。

    返回 (collected_records, failed_collect_count)。
    checkpoint 记录 status: collected(成功) / failed_collect(重试后仍失败)。
    resume 时 failed_collect 样本默认跳过,除非显式指定 retry_failed。
    """
    checkpoint = load_checkpoint(checkpoint_file) if resume else {}
    collected: list[dict] = []
    failed = 0
    for item in items:
        cid = case_id(item)
        if resume and cid in checkpoint:
            existing = checkpoint[cid]
            if existing["status"] == "collected":
                collected.append(existing)
                print(f"  [resume] 复用已采集: {item.get('query', '')[:30]}")
                continue
            if existing["status"] == "failed_collect" and not retry_failed:
                failed += 1
                continue
        query = item["query"]
        print(f"  [{len(collected) + failed + 1}/{len(items)}] 请求回答: {query[:30]}...")
        answer, contexts = "", []
        for attempt in range(retries + 1):
            answer, contexts = stream_answer(base, token, query)
            if answer:
                break
            print(f"    [warn] 第 {attempt + 1} 次未获取到回答: {query[:30]}")
        record = {
            "case_id": cid,
            "query": query,
            "reference": item.get("golden_answer", ""),
            "ragas_group": ragas_group_of(item),
            "business_tags": item.get("business_tags", []),
            "answer": answer,
            "contexts": contexts,
        }
        if answer:
            record["status"] = "collected"
            collected.append(record)
        else:
            record["status"] = "failed_collect"
            record["error"] = "no answer after retries"
            failed += 1
        append_checkpoint(checkpoint_file, record)
    return collected, failed


def main():
    parser = argparse.ArgumentParser(description="RAGAS 生成质量评测")
    parser.add_argument("--base-url", default="http://localhost:9090/api/ragent")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin")
    parser.add_argument("--dataset", type=Path,
                        default=Path(__file__).parent / "datasets/industrial_eval.jsonl")
    parser.add_argument("--limit", type=int, default=12, help="非分层模式下的样本截取数(默认 12)")
    parser.add_argument("--per-group", type=int, default=None,
                        help="按 ragas_group 分层抽样,每组至多 N 条(默认 None 表示不启用分层)")
    parser.add_argument("--seed", type=int, default=202603, help="分层抽样随机种子(默认 202603)")
    parser.add_argument("--resume", action="store_true", help="从 checkpoint 断点恢复")
    parser.add_argument("--retry-failed", action="store_true",
                        help="resume 时对已标记 failed_collect 的样本重新采集(默认跳过)")
    parser.add_argument("--retries", type=int, default=2, help="采集失败重试次数(默认 2)")
    parser.add_argument("--eval-model", default="deepseek-ai/DeepSeek-V3.2",
                        help="RAGAS 打分 LLM 模型(默认 SiliconFlow DeepSeek-V3.2)")
    parser.add_argument("--eval-provider", choices=["siliconflow", "bailian"], default="siliconflow",
                        help="Evaluation model provider; siliconflow is the default and bailian remains optional.")
    parser.add_argument("--out", type=Path, default=Path(__file__).parent / "report/ragas_report.json")
    parser.add_argument("--runtime-profile", type=Path, default=DEFAULT_PROFILE,
                        help="Secret-free JSON profile of configured serving models.")
    parser.add_argument("--application-config", type=Path, default=DEFAULT_APPLICATION_CONFIG,
                        help="Effective application configuration file to fingerprint.")
    args = parser.parse_args()

    if args.eval_provider == "siliconflow":
        api_key = os.environ.get("SILICONFLOW_API_KEY", "").strip()
        llm_base, emb_model, required_key_name = SILICON_BASE, "Qwen/Qwen3-Embedding-8B", "SILICONFLOW_API_KEY"
    else:
        api_key = os.environ.get("BAILIAN_API_KEY", "").strip()
        llm_base, emb_model, required_key_name = BAILIAN_BASE, "text-embedding-v4", "BAILIAN_API_KEY"
    if not api_key:
        print(f"[error] {required_key_name} is not configured", file=sys.stderr)
        sys.exit(1)

    print(f"[ragas_eval] 登录 {args.base_url} ...")
    token = login(args.base_url, args.username, args.password)
    items = load_dataset(args.dataset)
    selected = select_items(items, args.per_group, args.limit, args.seed)
    print(f"[ragas_eval] 评测 {len(selected)} 条,打分服务商 {args.eval_provider} 模型 {args.eval_model}"
          f"{f',分层抽样每组 {args.per_group} 条' if args.per_group else ''}")

    # ---- 1. 采集回答与上下文(断点恢复 + 失败重试) ----
    checkpoint_file = checkpoint_path(args.out)
    collected, failed_collect = collect_records(
        selected, checkpoint_file, args.base_url, token, args.retries, args.resume, args.retry_failed)
    print(f"[ragas_eval] 采集完成:成功 {len(collected)} 条,失败 {failed_collect} 条")

    if not collected:
        print("[error] 无有效样本", file=sys.stderr)
        sys.exit(1)

    # ---- 2. RAGAS 评测(整体失败重试一次) ----
    print(f"[ragas_eval] 开始 RAGAS 评测({len(collected)} 样本)...")
    eval_llm = LangchainLLMWrapper(ChatOpenAI(
        model=args.eval_model, api_key=api_key, base_url=llm_base, temperature=0,
        request_timeout=180,
    ))
    eval_emb = LangchainEmbeddingsWrapper(OpenAIEmbeddings(
        model=emb_model, api_key=api_key, base_url=llm_base,
    ))
    samples = [SingleTurnSample(
        user_input=record["query"],
        response=record["answer"],
        retrieved_contexts=record["contexts"],
        reference=record["reference"],
    ) for record in collected]
    result = None
    last_error = None
    for attempt in range(2):
        try:
            result = evaluate(
                EvaluationDataset(samples=samples),
                metrics=list(ALL_METRICS),
                llm=eval_llm,
                embeddings=eval_emb,
            )
            break
        except Exception as e:  # noqa: BLE001
            last_error = e
            print(f"    [warn] RAGAS 打分第 {attempt + 1} 次失败: {e}", file=sys.stderr)
    if result is None:
        print(f"[error] RAGAS 打分失败: {last_error}", file=sys.stderr)
        sys.exit(1)

    # ---- 3. 汇总输出(分层 + CI + 失败统计 + 业务标签) ----
    df = result.to_pandas()
    df_records = df.to_dict(orient="records")
    # 防御 ragas 内部对样本的重排/过滤导致指标错位:行数不一致时显式失败而非静默错位
    if len(df_records) != len(collected):
        raise RuntimeError(
            f"ragas 返回 {len(df_records)} 行与样本数 {len(collected)} 不一致,指标可能错位")

    metric_names = [metric.name for metric in ALL_METRICS]
    per_sample = []
    failed_score = 0
    group_values = OrderedDict((group, {name: [] for name in metric_names}) for group in RAGAS_GROUP_ORDER)
    all_values = {name: [] for name in metric_names}
    business_tags_counter = Counter()

    for record, row in zip(collected, df_records):
        group = record["ragas_group"]
        row_values = {}
        for name in metric_names:
            value = row.get(name)
            if value is None or (isinstance(value, float) and math.isnan(value)):
                value = None
            else:
                value = float(value)
            row_values[name] = value
            if value is not None:
                group_values[group][name].append(value)
                all_values[name].append(value)
        # 核心指标(排除 answer_relevancy)全缺失才算打分失败
        core = [row_values.get("faithfulness"), row_values.get("context_precision"),
                row_values.get("context_recall")]
        if all(v is None for v in core):
            failed_score += 1
        for tag in record.get("business_tags") or []:
            business_tags_counter[tag] += 1
        per_sample.append({
            "case_id": record["case_id"],
            "query": record["query"],
            "ragas_group": group,
            "business_tags": record.get("business_tags", []),
            **{name: (round(row_values[name], 4) if row_values[name] is not None else None)
               for name in metric_names},
        })

    summary = {
        "samples": len(collected),
        **{name: metric_summary(all_values[name]) for name in metric_names},
    }
    group_counts = Counter(record["ragas_group"] for record in collected)
    groups = OrderedDict()
    for group in RAGAS_GROUP_ORDER:
        if group not in group_counts:
            continue
        values = group_values[group]
        groups[group] = {
            "n": group_counts[group],
            **{name: metric_summary(values[name]) for name in metric_names},
        }

    sample_status = {
        "scored": len(collected) - failed_score,
        "failed_collect": failed_collect,
        "failed_score": failed_score,
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump({
            "schema_version": REPORT_SCHEMA_VERSION,
            "summary": summary,
            "groups": groups,
            "sample_status": sample_status,
            "business_tags": dict(sorted(business_tags_counter.items())),
            "internal_metrics": list(INTERNAL_METRICS),
            "confidence_interval": {
                "method": "t_distribution_cornish_fisher",
                "level": 0.95,
                "note": "n<2 时 ci95_half 为 null;内部指标 answer_relevancy 不进入简历",
            },
            "execution_fingerprint": build_execution_fingerprint(
                Path(__file__), args.runtime_profile, args.application_config,
                extra={
                    "ragas_evaluator": {
                        "provider": args.eval_provider,
                        "model": args.eval_model,
                        "embedding_model": emb_model,
                    },
                    "sampling": {
                        "per_group": args.per_group,
                        "seed": args.seed,
                        "limit": args.limit,
                    },
                }),
            "per_sample": per_sample,
        },
                  f, ensure_ascii=False, indent=2)

    print("\n================ RAGAS 指标汇总 ================")
    for name in metric_names:
        entry = summary[name]
        ci = f" ±{entry['ci95_half']}" if entry["ci95_half"] is not None else ""
        print(f"{name}: {entry['mean']}{ci}")
    print(f"样本: scored={sample_status['scored']} "
          f"failed_collect={sample_status['failed_collect']} "
          f"failed_score={sample_status['failed_score']}")
    if groups:
        print("\n分层指标:")
        for group, entry in groups.items():
            print(f"  {group}(n={entry['n']}): "
                  f"faithfulness={entry['faithfulness']['mean']} "
                  f"context_precision={entry['context_precision']['mean']} "
                  f"context_recall={entry['context_recall']['mean']}")
    print(f"报告输出: {args.out}")


if __name__ == "__main__":
    main()
