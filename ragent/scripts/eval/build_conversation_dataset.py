#!/usr/bin/env python3
"""Build 20 source-grounded R3 conversations for real server-memory evaluation."""

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

GENERATOR_VERSION = "industrial-conversation-r3-curated-1"
DEFAULT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_FAQ = DEFAULT_ROOT / "bootstrap/data/faq/industrial_faq.jsonl"
DEFAULT_OUT = Path(__file__).resolve().parent / "datasets/industrial_conversation_r3.jsonl"


def spec(split, conversation_type, canonical_target_query, turns, context_notes):
    return {
        "split": split,
        "conversation_type": conversation_type,
        "canonical_target_query": canonical_target_query,
        "turns": turns,
        "context_notes": context_notes,
    }


CASE_SPECS = (
    spec("tuning", "ellipsis", "高炉炉缸侧壁温度异常升高如何判断是否为侵蚀严重？",
         ["高炉炉缸最近侧壁温度一直升。", "相邻测点温差也在变大。", "到什么程度算侵蚀严重？"], "目标轮省略高炉炉缸与侧壁温度"),
    spec("tuning", "ellipsis", "转炉氧枪频繁漏水可能是什么原因？如何诊断？",
         ["转炉氧枪这两天频繁漏水。", "一般是什么原因，怎么诊断？"], "目标轮省略设备与漏水现象"),
    spec("tuning", "ellipsis", "连铸机结晶器振动异常导致铸坯表面振痕过深，可能原因有哪些？",
         ["连铸坯表面振痕突然变深了。", "结晶器振动也不太正常。", "可能原因有哪些？"], "目标轮省略全部实体并依赖前两轮组合"),
    spec("tuning", "ellipsis", "冷轧机主电机电流异常升高，如何快速判断故障源？",
         ["冷轧机主电机电流在往上升。", "负荷也有波动。", "怎么快速判断故障源？"], "目标轮省略设备、指标与症状"),
    spec("tuning", "ellipsis", "烧结机台车轮卡死的常见原因及处理方法是什么？",
         ["烧结机有个台车轮卡死了。", "常见原因和处理办法呢？"], "目标轮省略烧结机台车轮"),
    spec("tuning", "cross_turn_reference", "高炉冷却系统日常维护要点有哪些？",
         ["高炉冷却系统最近温差有点大。", "回水温度也有变化。", "它平时维护要重点看哪些？"], "它指代高炉冷却系统"),
    spec("tuning", "cross_turn_reference", "转炉托圈冷却系统如何进行日常维护？",
         ["转炉托圈冷却系统运行时间挺长了。", "这个系统日常怎么维护？"], "这个系统指代托圈冷却系统"),
    spec("tuning", "cross_turn_reference", "结晶器冷却系统应如何进行周期性保养？",
         ["连铸机结晶器冷却系统准备做保养。", "它的周期性项目有哪些？"], "它指代结晶器冷却系统"),
    spec("tuning", "cross_turn_reference", "轧机支撑辊轴承的定期保养要求是什么？",
         ["热轧机支撑辊轴承要安排定保。", "这个部件多久保养，要求是什么？"], "这个部件指代支撑辊轴承"),
    spec("tuning", "cross_turn_reference", "焦炉炉门框和炉门的日常维护要点有哪些？",
         ["焦炉炉门框和炉门最近密封不太好。", "先说说可能影响。", "它们日常维护有哪些要点？"], "它们跨越辅助问题指代炉门框和炉门"),

    spec("frozen", "ellipsis", "凝汽器真空度骤降（如从-92kPa降至-80kPa以下），应排查哪些环节？",
         ["凝汽器真空从负九十二掉到负八十以下了。", "下降速度还很快。", "应该排查哪些环节？"], "目标轮省略凝汽器与真空度"),
    spec("frozen", "ellipsis", "发电机运行中出现电压波动超过±5%的原因可能有哪些？",
         ["发电机电压波动已经超过正负百分之五。", "可能有哪些原因？"], "目标轮省略设备、电压与阈值"),
    spec("frozen", "ellipsis", "锅炉运行中出现汽包水位异常波动，可能的原因有哪些？",
         ["锅炉汽包水位来回波动。", "给水流量看着也不稳。", "一般是什么原因？"], "目标轮依赖前两轮症状组合"),
    spec("frozen", "ellipsis", "开关柜内断路器频繁跳闸，可能原因有哪些？",
         ["高压开关柜里的断路器最近老跳。", "保护信号也反复出现。", "可能原因有哪些？"], "目标轮省略开关柜断路器"),
    spec("frozen", "ellipsis", "冷却塔风机振动异常增大，可能原因有哪些？",
         ["冷却塔风机振动越来越大。", "通常从哪几方面找原因？"], "目标轮省略设备与振动现象"),
    spec("frozen", "cross_turn_reference", "汽轮机大修期间，如何检查和调整推力轴承间隙？",
         ["汽轮机大修要检查推力轴承。", "现在准备测轴向间隙。", "它的间隙怎么检查和调整？"], "它指代推力轴承"),
    spec("frozen", "cross_turn_reference", "发电机励磁系统碳刷更换周期和安装要求是什么？",
         ["发电机励磁系统的碳刷准备更换。", "这个部件多久换，安装有什么要求？"], "这个部件指代励磁碳刷"),
    spec("frozen", "cross_turn_reference", "锅炉停用一周以上应采取何种保养措施？",
         ["这台锅炉计划停用十天。", "这种情况该采取什么保养措施？"], "这种情况指代锅炉停用十天"),
    spec("frozen", "cross_turn_reference", "变压器定期维护中油样检测的周期和关键指标是什么？",
         ["主变要做定期维护，包含油样检测。", "这个检测周期多久，关键指标看什么？"], "这个检测指代变压器油样检测"),
    spec("frozen", "cross_turn_reference", "脱硫塔除雾器应如何进行定期维护保养？",
         ["脱硫塔出口压差有变化。", "怀疑是除雾器的问题。", "它定期怎么维护保养？"], "它跨越诊断轮指代脱硫塔除雾器"),
)


def load_jsonl(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def source_display_path(path: Path) -> str:
    try:
        return path.resolve().relative_to(DEFAULT_ROOT.resolve()).as_posix()
    except ValueError:
        return path.resolve().as_posix()


def build_cases(faq: list[dict], source_path: Path = DEFAULT_FAQ,
                specs=CASE_SPECS) -> list[dict]:
    by_question = {item["question"]: item for item in faq}
    sequence = Counter()
    cases = []
    seen = set()
    for item_spec in specs:
        canonical = item_spec["canonical_target_query"]
        if canonical not in by_question:
            raise ValueError(f"source question not found: {canonical}")
        if canonical in seen:
            raise ValueError(f"duplicate source question: {canonical}")
        seen.add(canonical)
        source = by_question[canonical]
        split = item_spec["split"]
        sequence[split] += 1
        turns = [{"role": "user", "content": content} for content in item_spec["turns"]]
        cases.append({
            "schema_version": 1,
            "case_type": "conversation",
            "id": f"r3-conversation-{split}-{sequence[split]:03d}",
            "scene": "colloquial",
            "split": split,
            "conversation_type": item_spec["conversation_type"],
            "turns": turns,
            "target_turn_index": len(turns) - 1,
            "canonical_target_query": canonical,
            "context_notes": item_spec["context_notes"],
            "golden_answer": source["answer"],
            "expected_channels": ["VECTOR_GLOBAL", "INTENT_DIRECTED"],
            "golden_source_ids": [source["source_doc"]],
            "ragas_group": "noise",
            "business_tags": [source["category"], item_spec["conversation_type"]],
            "provenance": {
                "source_file": source_display_path(source_path),
                "source_record_id": canonical,
            },
            "generator_version": GENERATOR_VERSION,
        })
    validate_cases(cases)
    return cases


def validate_cases(cases: list[dict]) -> None:
    if len(cases) != 20:
        raise ValueError(f"expected 20 conversations, got {len(cases)}")
    if Counter(case["split"] for case in cases) != {"tuning": 10, "frozen": 10}:
        raise ValueError("conversation split balance failed")
    if Counter(case["conversation_type"] for case in cases) != {
            "ellipsis": 10, "cross_turn_reference": 10}:
        raise ValueError("conversation type balance failed")
    if Counter(len(case["turns"]) for case in cases) != {2: 10, 3: 10}:
        raise ValueError("conversation length balance failed")


def render(cases: list[dict]) -> str:
    return "".join(json.dumps(case, ensure_ascii=False) + "\n" for case in cases)


def manifest(cases: list[dict], source_path: Path) -> dict:
    return {
        "generator_version": GENERATOR_VERSION,
        "count": len(cases),
        "split_counts": dict(Counter(case["split"] for case in cases)),
        "conversation_type_counts": dict(Counter(case["conversation_type"] for case in cases)),
        "turn_count_distribution": {str(k): v for k, v in Counter(len(case["turns"]) for case in cases).items()},
        "source_file": source_display_path(source_path),
        "source_sha256": hashlib.sha256(source_path.read_bytes()).hexdigest(),
        "human_evaluation": False,
        "assistant_history": "generated and persisted by the system under test",
        "gold_validation": "target answer and source document resolved from canonical FAQ record",
    }


def outputs(cases: list[dict], out: Path, source_path: Path) -> dict[Path, str]:
    return {
        out: render(cases),
        out.with_name(out.stem + "_tuning.jsonl"): render([c for c in cases if c["split"] == "tuning"]),
        out.with_name(out.stem + "_frozen.jsonl"): render([c for c in cases if c["split"] == "frozen"]),
        out.with_suffix(".manifest.json"): json.dumps(
            manifest(cases, source_path), ensure_ascii=False, indent=2) + "\n",
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--faq", type=Path, default=DEFAULT_FAQ)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    cases = build_cases(load_jsonl(args.faq), args.faq)
    artifacts = outputs(cases, args.out, args.faq)
    if args.check:
        for path, expected in artifacts.items():
            if not path.exists() or path.read_text(encoding="utf-8") != expected:
                raise ValueError(f"generated artifact mismatch: {path}")
        print("conversation dataset artifacts match source-grounded specification")
        return
    for path, content in artifacts.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    print(json.dumps(manifest(cases, args.faq), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
