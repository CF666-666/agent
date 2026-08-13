#!/usr/bin/env python3
"""Build the auditable 40-case R3 single-turn query-noise dataset."""

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

GENERATOR_VERSION = "industrial-noise-r3-curated-1"
DEFAULT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_FAQ = DEFAULT_ROOT / "bootstrap/data/faq/industrial_faq.jsonl"
DEFAULT_OUT = Path(__file__).resolve().parent / "datasets/industrial_noise_r3.jsonl"
NOISE_TYPES = ("typo_homophone", "alias_synonym", "unit_format", "ellipsis_word_order")


def spec(split: str, noise_type: str, canonical_query: str, query: str,
         mutation_notes: str) -> dict:
    return {
        "split": split,
        "noise_type": noise_type,
        "canonical_query": canonical_query,
        "query": query,
        "mutation_notes": mutation_notes,
    }


# Explicit cases are intentional: R3 measures realistic, auditable mutations and
# must not regress to generic colloquial-prefix templates.
CASE_SPECS = (
    spec("tuning", "typo_homophone", "高炉冷却壁出现漏水故障如何诊断？",
         "高炉冷确壁漏水咋诊断？", "冷却→冷确，压缩口语表达"),
    spec("tuning", "typo_homophone", "转炉倾动系统出现异响和振动，应如何排查？",
         "转炉顷动系统又响又振，怎么查？", "倾动→顷动，症状口语化"),
    spec("tuning", "typo_homophone", "连铸过程中拉速突然波动，如何快速诊断故障源？",
         "连铸拉数突然不稳，先排查哪？", "拉速→拉数，省略故障源"),
    spec("tuning", "typo_homophone", "热轧机液压系统压力波动大，如何排查？",
         "热扎机液压压力老跳，咋排查？", "热轧→热扎，波动口语化"),
    spec("tuning", "typo_homophone", "烧结机主抽风机振动超标的可能原因有哪些？",
         "烧结机主抽风机震动超标啥原因？", "振动→震动，删除书面成分"),
    spec("tuning", "alias_synonym", "高炉炉顶装料设备（如料车或无钟）操作有哪些关键规范？",
         "高炉无钟炉顶上料要守哪些规程？", "装料设备→无钟炉顶，上料同义替换"),
    spec("tuning", "alias_synonym", "转炉吹炼过程中氧枪操作有哪些关键规程？",
         "转炉吹氧时枪位和供氧怎么控？", "吹炼/氧枪改为现场简称表达"),
    spec("tuning", "alias_synonym", "连铸机扇形段辊组的日常维护保养内容有哪些？",
         "连铸扇形段辊列平时保养啥？", "辊组→辊列，维护保养→保养"),
    spec("tuning", "alias_synonym", "热轧机齿轮箱润滑系统应如何维护？",
         "热轧主减速箱的油路平时怎么养护？", "齿轮箱→主减速箱，润滑系统→油路"),
    spec("tuning", "alias_synonym", "焦炉四大车（装煤车、推焦车、拦焦车、熄焦车）联合作业时的安全联锁要求是什么？",
         "焦炉四大车联动作业要满足哪些联锁？", "使用现场统称四大车并省略枚举"),
    spec("tuning", "unit_format", "高炉休风复风时如何控制风量恢复节奏？",
         "高炉复风从三四成往上加时，多久加一次、每次加多少？", "30%–40%改为口语比例并拆分参数问法"),
    spec("tuning", "unit_format", "转炉炉衬异常剥落如何判断是否需停炉修补？",
         "转炉炉衬掉到多厚、温度高到多少就得停炉补？", "把阈值问题改为无单位口语表达"),
    spec("tuning", "unit_format", "轧辊表面出现周期性裂纹，如何判断是否需更换？",
         "轧辊裂纹一圈圈出现，裂多深多长要换辊？", "周期性与尺寸阈值改为现场表达"),
    spec("tuning", "unit_format", "烧结终点温度异常波动如何诊断？",
         "烧结终温上下窜五十来度，查哪些参数？", "±50℃改为自然语言数值"),
    spec("tuning", "unit_format", "高炉炉缸侧壁温度异常升高如何判断是否为侵蚀严重？",
         "炉缸侧壁到三百度、相邻点差五十多度，算侵蚀严重吗？", "300℃与50℃阈值改为中文口语数值"),
    spec("tuning", "ellipsis_word_order", "高炉开炉送风操作的关键步骤和参数是什么？",
         "烘炉好了准备送风，先开多少，后面怎么加？", "省略设备主语，以现场阶段作上下文"),
    spec("tuning", "ellipsis_word_order", "转炉兑铁前必须完成哪些安全确认步骤？",
         "要兑铁了，炉前这几项先确认啥？", "省略转炉并改为碎片化问句"),
    spec("tuning", "ellipsis_word_order", "更换结晶器时的操作流程和注意事项是什么？",
         "结晶器要换，先停哪些、装完查什么？", "省略连铸背景并重排操作阶段"),
    spec("tuning", "ellipsis_word_order", "处理卡钢事故时的安全操作要点是什么？",
         "卡住了，人过去处理前必须先做啥？", "省略钢材实体，保留事故语境"),
    spec("tuning", "ellipsis_word_order", "焦炉推焦过程中阻力异常增大，如何判断故障源？",
         "推着越来越费劲，先看炉墙还是机械？", "省略焦炉/阻力术语并给出口语候选"),

    spec("frozen", "typo_homophone", "汽轮机运行中振动突然增大至0.08mm以上，可能原因有哪些？",
         "气轮机振动突然过0.08毫米，可能哪坏了？", "汽轮机→气轮机，单位中文化"),
    spec("frozen", "typo_homophone", "发电机轴承温度异常升高至95℃以上应如何处理？",
         "发电机轴成温度过95度怎么处置？", "轴承→轴成，℃改口语度"),
    spec("frozen", "typo_homophone", "锅炉主蒸汽压力波动超过±0.5MPa，应如何处理？",
         "锅炉主蒸气压力上下超0.5MPa咋处理？", "蒸汽→蒸气，±改为上下"),
    spec("frozen", "typo_homophone", "变压器差动保护动作跳闸后应如何排查故障？",
         "变压器差洞保护跳了怎么查？", "差动→差洞，跳闸口语压缩"),
    spec("frozen", "typo_homophone", "脱硫塔出口SO₂浓度突然升高，如何快速诊断？",
         "脱流塔出口二氧化硫突然高了先查啥？", "脱硫→脱流，化学式中文化"),
    spec("frozen", "alias_synonym", "汽轮机调速系统（DEH）定期维护应包含哪些关键项目？",
         "汽机DEH定检都做哪些项目？", "汽轮机→汽机，定期维护→定检"),
    spec("frozen", "alias_synonym", "发电机定子绕组的日常维护保养项目有哪些？",
         "发电机定子线圈日常保养查什么？", "绕组→线圈，项目口语化"),
    spec("frozen", "alias_synonym", "锅炉受热面吹灰频次和参数如何设定？",
         "锅炉水冷壁和过热器多久吹一次灰，参数怎么定？", "受热面替换为典型部件枚举"),
    spec("frozen", "alias_synonym", "变压器投入运行前应执行哪些操作步骤？",
         "主变投运前要走哪些步骤？", "变压器→主变，投入运行→投运"),
    spec("frozen", "alias_synonym", "脱硫塔循环泵机械密封泄漏如何处理与预防？",
         "脱硫循泵机封漏了怎么处理，之后咋防？", "循环泵→循泵，机械密封→机封"),
    spec("frozen", "unit_format", "汽轮机启动过程中出现胀差异常（正胀差＞+3.5mm），如何处理？",
         "汽机正胀差超过三点五毫米该怎么控？", "符号和小数改为中文口语"),
    spec("frozen", "unit_format", "发电机输出频率不稳定（偏离50Hz±0.5Hz）如何诊断？",
         "发电频率在五十赫兹上下晃半个点，怎么查？", "50Hz±0.5Hz改为自然语言"),
    spec("frozen", "unit_format", "锅炉排烟温度突然升高至180℃以上，如何诊断？",
         "锅炉烟温一下到一百八十多度，先排哪几处？", "180℃改为中文口语并省略排烟"),
    spec("frozen", "unit_format", "变压器运行中油温异常升高至95℃以上，可能原因有哪些？",
         "主变油温95度往上走，一般是什么原因？", "设备简称与温度单位口语化"),
    spec("frozen", "unit_format", "冷却塔出水温度持续高于设计值（如＞35℃），如何排查？",
         "冷却塔出水一直三十五度以上，按啥顺序查？", "比较符号与℃改为自然语言"),
    spec("frozen", "ellipsis_word_order", "汽轮机冷态启动时，冲转前需满足哪些关键参数条件？",
         "冷态准备冲转了，哪些数没到位不能开？", "省略汽轮机并以作业阶段指代"),
    spec("frozen", "ellipsis_word_order", "启动发电机前必须执行哪些操作步骤？",
         "准备起机，前面的电气检查按啥顺序？", "省略发电机并使用起机现场用语"),
    spec("frozen", "ellipsis_word_order", "锅炉正常停炉操作的关键步骤是什么？",
         "要正常停了，先减负荷还是先停燃料？", "省略锅炉，以候选动作表达语序疑问"),
    spec("frozen", "ellipsis_word_order", "开关柜从运行状态转为检修状态的标准操作步骤是什么？",
         "柜子要从运行转检修，手车、接地刀先动哪个？", "开关柜→柜子，拆分关键动作顺序"),
    spec("frozen", "ellipsis_word_order", "冷却塔电机频繁跳闸，可能是什么问题？",
         "这个风机电机老跳，电气还是机械问题？", "用指示词省略冷却塔并加入候选方向"),
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
    seen_questions: set[str] = set()
    cases = []
    sequence = Counter()
    source_file = source_display_path(source_path)
    for item_spec in specs:
        canonical = item_spec["canonical_query"]
        if canonical not in by_question:
            raise ValueError(f"source question not found: {canonical}")
        if canonical in seen_questions:
            raise ValueError(f"duplicate source question: {canonical}")
        seen_questions.add(canonical)
        source = by_question[canonical]
        sequence[item_spec["split"]] += 1
        cases.append({
            "schema_version": 1,
            "case_type": "single_turn",
            "id": f"r3-noise-{item_spec['split']}-{sequence[item_spec['split']]:03d}",
            "query": item_spec["query"],
            "canonical_query": canonical,
            "mutation_notes": item_spec["mutation_notes"],
            "noise_type": item_spec["noise_type"],
            "golden_answer": source["answer"],
            "scene": "colloquial",
            "split": item_spec["split"],
            "expected_channels": ["VECTOR_GLOBAL", "INTENT_DIRECTED"],
            "golden_source_ids": [source["source_doc"]],
            "business_tags": [source["category"], item_spec["noise_type"]],
            "ragas_group": "noise",
            "provenance": {
                "source_file": source_file,
                "source_record_id": canonical,
            },
            "generator_version": GENERATOR_VERSION,
        })
    validate_cases(cases)
    return cases


def validate_cases(cases: list[dict]) -> None:
    if len(cases) != 40:
        raise ValueError(f"expected 40 cases, got {len(cases)}")
    split_counts = Counter(case["split"] for case in cases)
    if split_counts != {"tuning": 20, "frozen": 20}:
        raise ValueError(f"invalid split counts: {dict(split_counts)}")
    noise_counts = Counter(case["noise_type"] for case in cases)
    expected = {noise_type: 10 for noise_type in NOISE_TYPES}
    if noise_counts != expected:
        raise ValueError(f"invalid noise counts: {dict(noise_counts)}")
    for split in ("tuning", "frozen"):
        counts = Counter(case["noise_type"] for case in cases if case["split"] == split)
        if counts != {noise_type: 5 for noise_type in NOISE_TYPES}:
            raise ValueError(f"invalid {split} noise counts: {dict(counts)}")
    if any(case["query"] == case["canonical_query"] for case in cases):
        raise ValueError("noise query must differ from canonical query")


def render(cases: list[dict]) -> str:
    return "".join(json.dumps(case, ensure_ascii=False) + "\n" for case in cases)


def manifest(cases: list[dict], source_path: Path) -> dict:
    return {
        "generator_version": GENERATOR_VERSION,
        "count": len(cases),
        "split_counts": dict(Counter(case["split"] for case in cases)),
        "noise_type_counts": dict(Counter(case["noise_type"] for case in cases)),
        "source_file": source_display_path(source_path),
        "source_sha256": hashlib.sha256(source_path.read_bytes()).hexdigest(),
        "human_evaluation": False,
        "authoring_method": "explicit curated mutations with source-grounded goldens",
        "gold_validation": "canonical question, answer, and source document resolved from FAQ record",
    }


def outputs(cases: list[dict], out: Path, source_path: Path) -> dict[Path, str]:
    return {
        out: render(cases),
        out.with_name(out.stem + "_tuning.jsonl"): render(
            [case for case in cases if case["split"] == "tuning"]),
        out.with_name(out.stem + "_frozen.jsonl"): render(
            [case for case in cases if case["split"] == "frozen"]),
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
        print("noise dataset artifacts match explicit source-grounded specification")
        return
    for path, content in artifacts.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    print(json.dumps(manifest(cases, args.faq), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
