#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""补采集:对 select_items 选中的 60 条中,不在 checkpoint 里的样本补采集
(即缺失的 3 条 relation),append 到 checkpoint。不重打分。"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from ragas_eval import login, stream_answer, load_dataset  # noqa: E402
from ragas_eval_core import (  # noqa: E402
    append_checkpoint, case_id, checkpoint_path, load_checkpoint, ragas_group_of,
    select_items,
)

BASE = "http://localhost:9090/api/ragent"
CHECKPOINT = Path("report/r5d_20260815/ragas_report.checkpoint.jsonl")
DATASET = Path("datasets/industrial_main_r5.jsonl")
PER_GROUP = 15
SEED = 202603


def main() -> int:
    token = login(BASE, "admin", "admin")
    items = load_dataset(DATASET)
    selected = select_items(items, PER_GROUP, 12, SEED)
    print(f"selected {len(selected)} 条")

    checkpoint = load_checkpoint(CHECKPOINT)
    missing = [item for item in selected if case_id(item) not in checkpoint]
    print(f"缺失待补采: {len(missing)} 条")
    for item in missing:
        print(f"  - {case_id(item)} [{ragas_group_of(item)}] {item['query'][:40]}")

    if not missing:
        print("无需补采")
        return 0

    for item in missing:
        cid = case_id(item)
        query = item["query"]
        print(f"[collect] {cid}: {query[:40]}")
        answer, contexts = "", []
        for attempt in range(3):
            try:
                answer, contexts = stream_answer(BASE, token, query)
            except Exception as e:  # noqa: BLE001
                print(f"    [warn] 第 {attempt + 1} 次异常: {e}")
                answer, contexts = "", []
            if answer:
                break
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
            print(f"    [ok] answer={len(answer)} 字, contexts={len(contexts)}")
        else:
            record["status"] = "failed_collect"
            record["error"] = "no answer after retries"
            print(f"    [fail] 无回答")
        append_checkpoint(CHECKPOINT, record)

    # 复查
    checkpoint2 = load_checkpoint(CHECKPOINT)
    still_missing = [case_id(item) for item in selected if case_id(item) not in checkpoint2]
    print(f"\n补采后仍缺失: {len(still_missing)} 条 {still_missing}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
