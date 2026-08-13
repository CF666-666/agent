# Controlled baseline reproduction

```powershell
python scripts/eval/retrieval_eval.py --base-url http://localhost:9090/api/ragent --dataset scripts/eval/datasets/industrial_eval_v2.jsonl --label R0-E-controlled-full-chain --runtime-label r0e-stable-index-deadline --request-timeout 20 --retrieval-only --warmup-count 1 --scenes fact --out scripts/eval/report/baselines/<run>/fact.json
python scripts/eval/retrieval_eval.py --base-url http://localhost:9090/api/ragent --dataset scripts/eval/datasets/industrial_eval_v2.jsonl --label R0-E-controlled-full-chain --runtime-label r0e-stable-index-deadline --request-timeout 20 --retrieval-only --warmup-count 1 --scenes colloquial --out scripts/eval/report/baselines/<run>/colloquial.json
python scripts/eval/retrieval_eval.py --base-url http://localhost:9090/api/ragent --dataset scripts/eval/datasets/industrial_eval_v2.jsonl --label R0-E-controlled-full-chain --runtime-label r0e-stable-index-deadline --request-timeout 20 --retrieval-only --warmup-count 1 --scenes image --out scripts/eval/report/baselines/<run>/image.json
python scripts/eval/retrieval_eval.py --base-url http://localhost:9090/api/ragent --dataset scripts/eval/datasets/industrial_eval_v2.jsonl --label R0-E-controlled-full-chain --runtime-label r0e-stable-index-deadline --request-timeout 20 --retrieval-only --warmup-count 1 --scenes relation --out scripts/eval/report/baselines/<run>/relation.json
python scripts/eval/merge_eval_reports.py --out scripts/eval/report/baselines/<run>/merged.json scripts/eval/report/baselines/<run>/fact.json scripts/eval/report/baselines/<run>/colloquial.json scripts/eval/report/baselines/<run>/image.json scripts/eval/report/baselines/<run>/relation.json
```
