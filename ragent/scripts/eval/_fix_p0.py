#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""修复独立复审 P0:
1. retry.json 的非法 NaN -> null(不重跑,直接清洗)
2. checkpoint 96 行重复 -> 60 条唯一(保留最后一条,即重试后最新)
"""
import json
import math
from collections import OrderedDict
from pathlib import Path

REPORT_DIR = Path("report/r5d_20260815")


def clean_obj(obj):
    """递归把 NaN 转 None。"""
    if isinstance(obj, float) and math.isnan(obj):
        return None
    if isinstance(obj, dict):
        return {k: clean_obj(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [clean_obj(v) for v in obj]
    return obj


def fix_retry() -> None:
    path = REPORT_DIR / "ragas_report.retry.json"
    if not path.exists():
        print("retry.json 不存在,跳过")
        return
    data = json.loads(path.read_text(encoding="utf-8"))  # NaN 会被解析成 float('nan')
    cleaned = clean_obj(data)
    # 用 ensure_ascii=False + 手动保证无 NaN
    path.write_text(json.dumps(cleaned, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8")
    # 验证无 NaN
    text = path.read_text(encoding="utf-8")
    assert "NaN" not in text, "retry.json 仍含 NaN"
    print(f"[fix] retry.json 清洗完成, {len(cleaned)} 条")


def fix_checkpoint() -> None:
    path = REPORT_DIR / "ragas_report.checkpoint.jsonl"
    raw = [json.loads(x) for x in path.read_text(encoding="utf-8").splitlines()
           if x.strip()]
    print(f"[fix] checkpoint 原始 {len(raw)} 行")
    uniq = OrderedDict()
    for r in raw:
        uniq[r["case_id"]] = r  # 保留最后一条
    # 只保留 collected 状态
    collected = [r for r in uniq.values() if r.get("status") == "collected"]
    non_collected = [r for r in uniq.values() if r.get("status") != "collected"]
    print(f"[fix] 唯一 {len(uniq)} 条, collected {len(collected)} 条, 非 collected {len(non_collected)} 条")

    # 写干净 checkpoint(按 case_id 排序)
    path.write_text(
        "".join(json.dumps(r, ensure_ascii=False) + "\n"
                for r in sorted(collected, key=lambda x: x["case_id"])),
        encoding="utf-8")
    print(f"[fix] checkpoint 重写为 {len(collected)} 条 collected")


def main() -> None:
    fix_retry()
    fix_checkpoint()
    print("[fix] P0 修复完成")


if __name__ == "__main__":
    main()
