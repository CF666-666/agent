#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Pure, offline-testable helpers for the R5-C extended RAGAS runner.

This module deliberately imports only the Python standard library so the
sampling, checkpoint and statistics logic can be regression-tested without a
running backend or the ragas/langchain dependencies.
"""

from __future__ import annotations

import hashlib
import json
import math
import random
from collections import OrderedDict
from pathlib import Path

RAGAS_GROUP_ORDER = ("text", "noise", "image", "relation")
RAGAS_GROUPS = frozenset(RAGAS_GROUP_ORDER)

# 标准 scene → ragas_group 映射(legacy v2 数据)
SCENE_TO_GROUP = {
    "fact": "text",
    "image": "image",
    "relation": "relation",
    "colloquial": "noise",
}

REPORT_SCHEMA_VERSION = 2
# answer_relevancy 评分稳定性不足,只保留在内部报告,不进入简历
INTERNAL_METRICS = ("answer_relevancy",)


def case_id(record: dict) -> str:
    """稳定唯一键:优先 id 字段,回退到规范化记录哈希。

    用 id 而非 query 作为断点恢复键,避免"同一 query 配不同 golden_source_ids"
    的样本在 resume 时互相覆盖。
    """
    record_id = record.get("id")
    if record_id is not None and str(record_id) != "":
        return str(record_id)
    payload = json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:24]


def ragas_group_of(record: dict) -> str:
    """推断记录所属的 RAGAS 分层。

    优先级:显式 ragas_group 字段 > 标准英文 scene 映射 > 历史中文业务标签 scene 归 text。
    未知 scene 宽松归 text,避免旧数据集整体崩溃。
    """
    explicit = record.get("ragas_group")
    if explicit is not None:
        if explicit not in RAGAS_GROUPS:
            raise ValueError(f"unsupported ragas_group: {explicit}")
        return explicit
    scene = record.get("scene")
    if scene in SCENE_TO_GROUP:
        return SCENE_TO_GROUP[scene]
    return "text"


def is_conversation_record(record: dict) -> bool:
    """多轮会话记录用 turns 而非 query,单轮 RAGAS 评测必须过滤。"""
    return record.get("case_type") == "conversation" or "turns" in record


def stratify_by_group(items: list[dict]) -> OrderedDict:
    grouped = OrderedDict((group, []) for group in RAGAS_GROUP_ORDER)
    for item in items:
        grouped[ragas_group_of(item)].append(item)
    return grouped


def stratified_sample(items: list[dict], per_group: int, seed: int) -> list[dict]:
    """固定种子分层抽样,每组至多 per_group 条,组内顺序确定性稳定。"""
    rng = random.Random(seed)
    grouped = stratify_by_group(items)
    selected: list[dict] = []
    for group in RAGAS_GROUP_ORDER:
        members = grouped[group][:]
        rng.shuffle(members)
        selected.extend(members[:per_group])
    return selected


def t_critical_0975(n: int):
    """95% 双侧置信区间的 t 分布临界值(Cornish-Fisher 展开,纯标准库)。

    n < 2 时无法估计标准差,返回 None。对 n>=5 与真实 t 分位数误差 < 0.001。
    """
    nu = n - 1
    if nu < 1:
        return None
    z = 1.959963984540054
    z2 = z * z
    z3 = z2 * z
    z5 = z3 * z2
    z7 = z5 * z2
    t = z
    t += (z3 + z) / (4 * nu)
    t += (5 * z5 + 16 * z3 + 3 * z) / (96 * nu * nu)
    t += (3 * z7 + 19 * z5 + 17 * z3 - 15 * z) / (384 * nu ** 3)
    return t


def mean_ci(values: list[float]):
    """返回 (mean, ci95_half)。忽略 None/NaN;样本数 <2 时 ci 为 None。

    无有效样本时 mean 为 None,与"实际得分 0"区分,避免误导报告读者。
    """
    finite = [v for v in values if v is not None and not (isinstance(v, float) and math.isnan(v))]
    n = len(finite)
    if n == 0:
        return None, None
    mean = sum(finite) / n
    if n < 2:
        return mean, None
    variance = sum((v - mean) ** 2 for v in finite) / (n - 1)
    std_err = math.sqrt(variance / n)
    return mean, std_err * t_critical_0975(n)


def metric_summary(values: list[float]) -> dict:
    mean, ci_half = mean_ci(values)
    return {
        "mean": round(mean, 4) if mean is not None else None,
        "ci95_half": round(ci_half, 4) if ci_half is not None else None,
    }


def checkpoint_path(out: Path) -> Path:
    return out.with_name(out.stem + ".checkpoint.jsonl")


def load_checkpoint(path: Path) -> dict:
    """读取 checkpoint,返回 {case_id: record}。容忍最后一行写入中断。"""
    checkpoint: dict = {}
    if not path.exists():
        return checkpoint
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
                checkpoint[record["case_id"]] = record
            except (json.JSONDecodeError, KeyError):
                # 中断时可能写入半行;畸形行(缺 case_id)也忽略
                continue
    return checkpoint


def append_checkpoint(path: Path, record: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(record, ensure_ascii=False) + "\n")


def load_dataset(path: Path):
    items = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                items.append(json.loads(line))
    return items


def select_items(items: list[dict], per_group, limit: int, seed: int) -> list[dict]:
    """按分层或全量截取选择评测样本,过滤多轮记录。"""
    eligible = [item for item in items if not is_conversation_record(item)]
    if per_group is not None:
        return stratified_sample(eligible, per_group, seed)
    if limit > 0:
        return eligible[:limit]
    return eligible
