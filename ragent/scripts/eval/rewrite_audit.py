#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R3-D: reproduce query rewrites offline and classify rewrite errors.

Reproduces the live rewrite path without touching the backend: same prompt
(prompt/user-question-rewrite.st), model (deepseek-ai/DeepSeek-V3.2 via
SiliconFlow), and sampling parameters (temperature 0.1 / top_p 0.3 /
max_tokens 512). Each rewritten query is then classified into one of:

- correct_completion      补全了 canonical 中的关键实体，无错误实体
- ineffective_rewrite     仍残留指代词，或未补全任何 golden 实体
- wrong_entity_injection  引入了 canonical 之外的领域实体
- should_clarify_but_didnt 历史有歧义却直接猜测而非澄清

The classifier is a pure, deterministic rule function (no LLM) so it is fully
unit-testable. LLM reproduction is isolated behind a pluggable callable.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

import requests

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parents[1]
DEFAULT_PROMPT = ROOT / "bootstrap/src/main/resources/prompt/user-question-rewrite.st"
DEFAULT_NOISE = SCRIPT_DIR / "datasets/industrial_noise_r3.jsonl"
DEFAULT_CONVERSATION = SCRIPT_DIR / "datasets/industrial_conversation_r3.jsonl"
DEFAULT_OUT = SCRIPT_DIR / "report/r3d_20260814/rewrite_audit.json"

REWRITE_MODEL = "deepseek-ai/DeepSeek-V3.2"
REWRITE_TEMPERATURE = 0.1
REWRITE_TOP_P = 0.3
REWRITE_MAX_TOKENS = 512

CLASSIFICATIONS = (
    "correct_completion",
    "ineffective_rewrite",
    "wrong_entity_injection",
    "should_clarify_but_didnt",
)

# 指代词残留判定词表。刻意不含单字「该/其/此/这/那」，
# 避免把「应该」「这样」「那么」等常见词误判为指代残留。
REFERENCE_WORDS = frozenset({
    "它", "他", "她", "它们", "他们", "她们",
    "这个", "那个", "这些", "那些",
})

# 澄清问句判定词表（用于「应澄清未澄清」类别，本数据集恒无此场景）。
CLARIFICATION_WORDS = frozenset({
    "请问", "具体指", "指的是", "哪一个", "哪个", "哪一种",
    "是不是", "你是想问", "请确认", "请选择",
})

# 领域实体词典：覆盖 60 个 R3 case 中 canonical 的关键设备/部件/系统实体。
# test_rewrite_audit 强制校验每个 canonical 至少命中一个实体，遗漏会被测试捕获。
DOMAIN_ENTITIES = (
    # 钢铁
    "高炉", "炉缸", "冷却壁", "炉顶", "装料设备", "无钟", "料车",
    "转炉", "氧枪", "炉衬", "倾动系统", "托圈",
    "连铸机", "连铸", "结晶器", "扇形段", "辊组", "拉矫机",
    "热轧机", "冷轧机", "齿轮箱", "主减速箱", "液压系统",
    "烧结机", "烧结", "台车", "主抽风机",
    "焦炉", "四大车", "装煤车", "推焦车", "拦焦车", "熄焦车", "炉门", "炉门框",
    "轧机", "轧辊", "支撑辊", "主电机", "卡钢",
    # 石化
    "裂解炉", "储罐", "反应釜", "换热器", "冷却塔", "风机",
    # 电力
    "凝汽器", "真空度", "汽轮机", "发电机", "锅炉", "汽包",
    "开关柜", "断路器", "变压器", "主变",
    "脱硫塔", "除雾器", "循环泵", "机械密封",
    "定子绕组", "励磁系统", "碳刷", "推力轴承", "轴承",
    "油样", "差动保护", "调速系统", "受热面", "水冷壁", "过热器",
    "叶轮", "引风机", "给水泵", "除氧器",
)

# 同义/简称归一化（确定性等价，不含上下位）：把现场简称或别名映射到规范实体，
# 避免把「主变」vs「变压器」这类等价替换误判为错误实体注入。
SYNONYM_EQUIVALENCE = {
    "主变": "变压器",
    "汽机": "汽轮机",
    "主减速箱": "齿轮箱",
    "循泵": "循环泵",
    "机封": "机械密封",
}

# 部件-整体覆盖（下位覆盖上位）：数据集锚定的已知关系。「受热面」在数据集中以
# 「水冷壁/过热器」枚举表达（build_noise_dataset 的 mutation_notes），故这两者视为
# 覆盖「受热面」而非与其等价。覆盖只影响 covered 判定，不参与注入归一化，避免把
# 任意部件替换误判为「等价实体」。
HYPERNYM_COVERAGE = {
    "受热面": frozenset({"水冷壁", "过热器"}),
}


def extract_entities(text: str, entities: tuple = DOMAIN_ENTITIES) -> list[str]:
    """Return domain entities that appear in text (order preserved)."""
    return [entity for entity in entities if entity in text]


def has_reference_word(text: str) -> bool:
    return any(word in text for word in REFERENCE_WORDS)


def is_clarification_query(text: str) -> bool:
    return any(word in text for word in CLARIFICATION_WORDS)


def _normalize_entity(entity: str, equivalence: dict) -> str:
    return equivalence.get(entity, entity)


def dedupe_overlapping(entities: list[str]) -> list[str]:
    """Drop shorter entities already contained in a longer entity
    (e.g. keep ``热轧机`` and drop ``轧机`` when both are present)."""
    result: list[str] = []
    for entity in sorted(entities, key=len, reverse=True):
        if any(entity in existing for existing in result):
            continue
        result.append(entity)
    return sorted(result)


def classify_rewrite(rewritten: str,
                     canonical: str,
                     domain_entities: tuple = DOMAIN_ENTITIES,
                     reference_words: frozenset = REFERENCE_WORDS,
                     equivalence: dict | None = None,
                     hypernym_coverage: dict | None = None,
                     source_entities: list[str] | None = None,
                     clarification_expected: bool = False) -> dict:
    """Deterministic rewrite classification. Returns a result dict with
    classification / injected_entities / covered_entities / reason.

    equivalence: synonym/abbreviation map normalized to a canonical entity so
    ``主变`` vs ``变压器`` are not misread as an injection.
    hypernym_coverage: part-whole map (``受热面 -> {水冷壁, 过热器}``); a
    rewritten hyponym is treated as COVERING the canonical hypernym for the
    coverage check only, never as an equivalence for the injection check.
    source_entities: entities already present in the original query/history;
    an injected entity that came from the source is an under-correction, not a
    hallucinated injection, so it is excluded from the injection set."""
    equivalence = equivalence or {}
    hypernym_coverage = hypernym_coverage or {}
    rewritten = (rewritten or "").strip()

    if clarification_expected and not is_clarification_query(rewritten):
        return {
            "classification": "should_clarify_but_didnt",
            "injected_entities": [], "covered_entities": [],
            "reason": "ambiguity_not_clarified",
        }
    if has_reference_word(rewritten):
        return {
            "classification": "ineffective_rewrite",
            "injected_entities": [], "covered_entities": [],
            "reason": "reference_word_residual",
        }

    rewritten_entities = {_normalize_entity(e, equivalence)
                          for e in extract_entities(rewritten, domain_entities)}
    canonical_entities = {_normalize_entity(e, equivalence)
                          for e in extract_entities(canonical, domain_entities)}
    injected = sorted(rewritten_entities - canonical_entities)
    if source_entities:
        source_norm = {_normalize_entity(e, equivalence) for e in source_entities}
        injected = [e for e in injected if e not in source_norm]
    if injected:
        return {
            "classification": "wrong_entity_injection",
            "injected_entities": injected, "covered_entities": [],
            "reason": "entity_not_in_canonical",
        }
    covered_direct = rewritten_entities & canonical_entities
    covered_hyponym = {
        rewritten_entity
        for canonical_entity in canonical_entities
        for rewritten_entity in rewritten_entities
        if rewritten_entity in hypernym_coverage.get(canonical_entity, ())
    }
    covered = dedupe_overlapping(sorted(covered_direct | covered_hyponym))
    if covered:
        return {
            "classification": "correct_completion",
            "injected_entities": [], "covered_entities": covered,
            "reason": "",
        }
    return {
        "classification": "ineffective_rewrite",
        "injected_entities": [], "covered_entities": [],
        "reason": "no_golden_entity_covered",
    }


def parse_rewrite_json(raw: str | None) -> dict | None:
    """Parse the LLM rewrite response. Returns {'rewrite', 'sub_questions'} or
    None on any parse failure (mirrors MultiQuestionRewriteService fallback)."""
    if not raw:
        return None
    text = raw.strip()
    if text.startswith("```"):
        lines = text.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        text = "\n".join(lines).strip()
    try:
        obj = json.loads(text)
    except (json.JSONDecodeError, ValueError):
        return None
    if not isinstance(obj, dict):
        return None
    rewrite = str(obj.get("rewrite") or "").strip()
    if not rewrite:
        return None
    subs = obj.get("sub_questions")
    if isinstance(subs, list):
        subs = [str(s).strip() for s in subs if str(s).strip()]
    else:
        subs = []
    if not subs:
        subs = [rewrite]
    return {"rewrite": rewrite, "sub_questions": subs}


def apply_mapping(text: str, source_term: str, target_term: str) -> str:
    """Faithful reproduction of QueryTermMappingUtil.applyMapping: a SINGLE
    pass that scans for source_term, and where the match is already the start
    of target_term it is copied verbatim (alreadyTarget protection) instead of
    being re-replaced. Unlike str.replace, it never re-processes text produced
    by a replacement."""
    if not text or not source_term:
        return text
    parts: list[str] = []
    idx = 0
    length = len(text)
    source_len = len(source_term)
    target_len = len(target_term)
    while idx < length:
        hit = text.find(source_term, idx)
        if hit < 0:
            parts.append(text[idx:length])
            break
        parts.append(text[idx:hit])
        already_target = (
            hit + target_len <= length and text.startswith(target_term, hit)
        )
        if already_target:
            parts.append(text[hit:hit + target_len])
            idx = hit + target_len
        else:
            parts.append(target_term)
            idx = hit + source_len
    return "".join(parts)


def normalize_text(text: str, mappings: list[tuple[str, str]]) -> str:
    """Reproduce QueryTermMappingService.normalize: apply each (source, target)
    mapping exactly once via apply_mapping, in priority order."""
    result = text
    for source, target in mappings:
        result = apply_mapping(result, source, target)
    return result


def build_messages(prompt_template: str, question: str,
                   history: list[dict] | None = None) -> list[dict]:
    """Faithfully reproduce MultiQuestionRewriteService.buildRewriteRequest:
    filter to USER/ASSISTANT, then skip max(0, original_size - 4) — note the
    skip uses the ORIGINAL history size (matching the backend, even though it
    differs from a filtered-size skip)."""
    messages: list[dict] = []
    if prompt_template.strip():
        messages.append({"role": "system", "content": prompt_template})
    if history:
        filtered = [m for m in history if m.get("role") in ("user", "assistant")]
        skip = max(0, len(history) - 4)
        messages.extend(filtered[skip:])
    messages.append({"role": "user", "content": question})
    return messages


def reproduce_rewrite(api_key: str,
                      model: str,
                      prompt_template: str,
                      question: str,
                      history: list[dict] | None = None,
                      timeout: int = 30) -> str:
    """Call SiliconFlow chat completions to reproduce the live rewrite."""
    payload = {
        "model": model,
        "messages": build_messages(prompt_template, question, history or []),
        "temperature": REWRITE_TEMPERATURE,
        "top_p": REWRITE_TOP_P,
        "max_tokens": REWRITE_MAX_TOKENS,
    }
    response = requests.post(
        "https://api.siliconflow.cn/v1/chat/completions",
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        json=payload, timeout=timeout,
    )
    response.raise_for_status()
    body = response.json()
    return body["choices"][0]["message"]["content"]


def audit_noise_case(case: dict, reproducer) -> dict:
    raw = reproducer(case["query"])
    parsed = parse_rewrite_json(raw)
    rewritten = parsed["rewrite"] if parsed else case["query"]
    source_entities = extract_entities(case["query"])
    result = classify_rewrite(
        rewritten, case["canonical_query"],
        equivalence=SYNONYM_EQUIVALENCE,
        hypernym_coverage=HYPERNYM_COVERAGE,
        source_entities=source_entities)
    result.update({
        "id": case["id"],
        "split": case.get("split"),
        "noise_type": case.get("noise_type"),
        "original_query": case["query"],
        "canonical": case["canonical_query"],
        "rewritten_query": rewritten,
        "sub_questions": parsed["sub_questions"] if parsed else [],
        "reproduction_ok": parsed is not None,
    })
    return result


def audit_conversation_case(case: dict, reproducer) -> dict:
    turns = case["turns"]
    target_index = case["target_turn_index"]
    history = [{"role": t["role"], "content": t["content"]} for t in turns[:target_index]]
    question = turns[target_index]["content"]
    raw = reproducer(question, history)
    parsed = parse_rewrite_json(raw)
    rewritten = parsed["rewrite"] if parsed else question
    source_text = question + " " + " ".join(t["content"] for t in turns[:target_index])
    source_entities = extract_entities(source_text)
    result = classify_rewrite(
        rewritten, case["canonical_target_query"],
        equivalence=SYNONYM_EQUIVALENCE,
        hypernym_coverage=HYPERNYM_COVERAGE,
        source_entities=source_entities)
    result.update({
        "id": case["id"],
        "split": case.get("split"),
        "conversation_type": case.get("conversation_type"),
        "original_query": question,
        "canonical": case["canonical_target_query"],
        "history": history,
        "rewritten_query": rewritten,
        "sub_questions": parsed["sub_questions"] if parsed else [],
        "reproduction_ok": parsed is not None,
    })
    return result


def summarize(cases: list[dict]) -> dict:
    counts = {classification: 0 for classification in CLASSIFICATIONS}
    for case in cases:
        counts[case["classification"]] += 1
    return {
        "total": len(cases),
        "classification_counts": counts,
        "reproduction_ok": sum(1 for c in cases if c["reproduction_ok"]),
        "reproduction_failed": sum(1 for c in cases if not c["reproduction_ok"]),
        "should_clarify_cases": 0,
        "should_clarify_note":
            "当前数据集每个 case 历史信息充分（唯一 golden），无历史歧义场景，"
            "该类别为 0，不虚构样本。",
    }


def load_jsonl(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def describe_dataset(path: Path) -> dict:
    import hashlib
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    try:
        display = path.resolve().relative_to(ROOT.resolve()).as_posix()
    except ValueError:
        display = path.as_posix()
    return {"path": display, "sha256": digest}


def main() -> None:
    parser = argparse.ArgumentParser(description="R3-D 查询改写错误审计")
    parser.add_argument("--api-key", default=os.environ.get("SILICONFLOW_API_KEY", ""))
    parser.add_argument("--model", default=REWRITE_MODEL)
    parser.add_argument("--prompt", type=Path, default=DEFAULT_PROMPT)
    parser.add_argument("--noise", type=Path, default=DEFAULT_NOISE)
    parser.add_argument("--conversation", type=Path, default=DEFAULT_CONVERSATION)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--limit", type=int, default=0, help="每类数据仅审计前 N 条(0=全部)")
    parser.add_argument("--term-mappings", type=Path, default=None,
                        help="JSON 文件 [[source, target], ...]，复现 DB 术语映射（默认空）")
    parser.add_argument("--dry-run", action="store_true",
                        help="不调用 LLM，用 canonical 作为 rewritten（验证分类器/报告结构）")
    args = parser.parse_args()

    prompt_template = args.prompt.read_text(encoding="utf-8")

    term_mappings: list[tuple[str, str]] = []
    if args.term_mappings:
        term_mappings = [tuple(pair) for pair in
                         json.loads(args.term_mappings.read_text(encoding="utf-8"))]

    def reproducer(question, history=None):
        normalized_question = normalize_text(question, term_mappings)
        if args.dry_run:
            return json.dumps({"rewrite": normalized_question,
                               "sub_questions": [normalized_question]},
                              ensure_ascii=False)
        if not args.api_key:
            raise ValueError("missing SILICONFLOW_API_KEY (or pass --api-key)")
        return reproduce_rewrite(args.api_key, args.model, prompt_template,
                                 normalized_question, history)

    noise_cases = load_jsonl(args.noise)
    conversation_cases = load_jsonl(args.conversation)
    if args.limit > 0:
        noise_cases = noise_cases[:args.limit]
        conversation_cases = conversation_cases[:args.limit]

    noise_results = [audit_noise_case(case, reproducer) for case in noise_cases]
    conversation_results = [audit_conversation_case(case, reproducer)
                            for case in conversation_cases]

    report = {
        "schema_version": 1,
        "mode": "dry-run" if args.dry_run else "live-reproduction",
        "rewrite_reproducer": {
            "model": args.model,
            "provider": "siliconflow",
            "temperature": REWRITE_TEMPERATURE,
            "top_p": REWRITE_TOP_P,
            "max_tokens": REWRITE_MAX_TOKENS,
            "prompt_path": describe_dataset(args.prompt)["path"],
            "term_mapping_rule_count": len(term_mappings),
            "normalize_note":
                "复现 QueryTermMappingService.normalize 前置步骤；评测环境 "
                "t_query_term_mapping enabled=1 共 0 条（已 psql 核验），normalize "
                "为 no-op，跳过与线上等价。若未来启用规则，需先 dump 规则并用 "
                "--term-mappings 传入。",
        },
        "datasets": {
            "noise": describe_dataset(args.noise),
            "conversation": describe_dataset(args.conversation),
        },
        "summary": {
            "noise": summarize(noise_results),
            "conversation": summarize(conversation_results),
        },
        "noise_cases": noise_results,
        "conversation_cases": conversation_results,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8")
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
