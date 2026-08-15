# -*- coding: utf-8 -*-
"""重试采集 contexts=0 的 relation 样本,contexts 非空则 append 覆盖 checkpoint。"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ragas_eval import login, stream_answer  # noqa: E402
from ragas_eval_core import append_checkpoint, load_checkpoint  # noqa: E402

BASE = "http://localhost:9090/api/ragent"
CHECKPOINT = Path("report/r5d_20260815/ragas_report.checkpoint.jsonl")
TARGETS = ("r2-relation-frozen-001", "r2-relation-frozen-014")


def main() -> int:
    token = login(BASE, "admin", "admin")
    checkpoint = load_checkpoint(CHECKPOINT)

    for cid in TARGETS:
        rec = checkpoint.get(cid)
        if not rec:
            print(cid, "NOT FOUND")
            continue
        if rec.get("contexts"):
            print(cid, "已非空,跳过")
            continue
        query = rec["query"]
        print(f"[retry] {cid}: {query[:40]}")
        best_answer, best_contexts = "", []
        for attempt in range(3):
            try:
                answer, contexts = stream_answer(BASE, token, query)
            except Exception as e:  # noqa: BLE001
                print(f"    第 {attempt + 1} 次异常: {e}")
                continue
            if contexts:
                best_answer, best_contexts = answer, contexts
                print(f"    第 {attempt + 1} 次拿到 {len(contexts)} 个 contexts")
                break
            else:
                print(f"    第 {attempt + 1} 次 contexts 仍为空")
        if best_contexts:
            new_rec = dict(rec)
            new_rec["answer"] = best_answer
            new_rec["contexts"] = best_contexts
            new_rec["status"] = "collected"
            append_checkpoint(CHECKPOINT, new_rec)
            print(f"    [ok] 已覆盖 {cid}, contexts={len(best_contexts)}")
        else:
            print(f"    [keep] {cid} 3 次均无 contexts,保留原记录")

    # 复查
    checkpoint2 = load_checkpoint(CHECKPOINT)
    for cid in TARGETS:
        r = checkpoint2.get(cid, {})
        print(f"最终 {cid}: contexts={len(r.get('contexts', []))}, answer={len(r.get('answer', ''))}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
