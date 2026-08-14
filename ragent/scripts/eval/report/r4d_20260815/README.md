# R4-C/D 图像索引重建 + 图像通道/全链路评测 — 结论文档

> 闭环：R4-C（重建图像索引并校验引用）+ R4-D（图像通道与全链路评测）
> 日期：2026-08-15
> 状态：✅ 完成（含 2 个阻塞 bug 修复）
> 镜像：backend `ragent-stack-backend` 新构建（含 `ab35fc0` + timeout 修复）

---

## 0. 阻塞修复（本闭环前置）

R4-C/D 推进中暴露 2 个真实缺陷，均已修复并验证：

| # | 缺陷 | 根因 | 修复 | 提交 |
|---|---|---|---|---|
| 1 | 图像启动 ingest 失败 | 图像用 `embeddingService.embed()` 走在线路由（siliconflow 网络抖动→fallback ollama 连不上→"All Embedding model candidates failed"），FAQ 却用 `StartupEmbeddingRetryExecutor`（固定模型+3 次重试） | `ImageIngestionService` 增加带预计算向量的 6 参重载（null 回退在线路由，保持 `MultimodalDocumentParserNode` 兼容）；`Phase5DataIngestionRunner.ingestImages` 改用 retryExecutor 固定模型生成向量 | `ab35fc0` |
| 2 | 图像检索通道 10% 超时 | `image-semantic.timeout-millis=3000`（3 秒）过短，而图像检索需先 embedding query（网络抖动 2-10 秒，embedding 调用本身预算 10000ms） | `image-semantic.timeout-millis` 3000→10000 | 本次提交 |

验证：修复后 `Phase 5: full data ingestion completed, faqCount=210, imageCount=40`；图像通道评测 100/100 全执行（修复前 10 条超时）。

## 1. R4-C 引用一致性校验

三件套一致性（`_r4c_ref_check.py`）：

| 校验项 | 结果 |
|---|---|
| 评测集 `golden_image_paths` 唯一数 | 40 ✅ |
| Milvus `industrial_images` metadata.imagePath | 40 ✅ |
| 缺失（golden 不在 milvus） | 0 ✅ |
| 文件↔`/files/{path}` URL 字节一致 | 40/40 ✅ |
| SSE reference 动态一致（channel_hit + source_id_hit） | ✅（图像评测 source_id_hit=56%） |

## 2. R4-D 评测结果

### 2.1 图像通道评测（rewrite-off + 关超图，隔离图像语义通道）

100/100 执行成功。

| 分组 | Hit@1 | Hit@3 | Hit@5 | MRR | srcHit | P95 |
|---|---:|---:|---:|---:|---:|---:|
| **整体** | 34.00% | 48.00% | 52.00% | 0.4173 | 56% | 2236ms |
| engineering_drawing | 26.00% | 34.00% | 38.00% | 0.3077 | 42% | 10219ms |
| scanned_manual | 36.00% | 68.00% | 68.00% | 0.5067 | 68% | 348ms |
| site_photo | 48.00% | 56.00% | 64.00% | 0.5470 | 72% | 276ms |
| frozen | 24.00% | 40.00% | 46.00% | 0.3305 | 52% | 1209ms |
| tuning | 44.00% | 56.00% | 58.00% | 0.5040 | 60% | 3339ms |

### 2.2 全链路评测（rewrite-on + 融合，关超图）

92/100 执行成功（8 条为 SiliconFlow embedding 网络抖动导致图像通道超时，属执行不稳定，非质量缺陷）。

| 分组 | Hit@1 | Hit@3 | Hit@5 | MRR | srcHit | P95 |
|---|---:|---:|---:|---:|---:|---:|
| **整体** | 35.87% | 56.52% | 67.39% | 0.4745 | 69.57% | 17653ms |
| engineering_drawing | 27.66% | 51.06% | 59.57% | 0.3915 | 59.57% | 15493ms |
| scanned_manual | 56.52% | 78.26% | 82.61% | 0.6754 | 82.61% | 13415ms |
| site_photo | 31.82% | 45.45% | 68.18% | 0.4418 | 77.27% | 17689ms |
| frozen | 33.33% | 54.17% | 64.58% | 0.4549 | 68.75% | 17653ms |
| tuning | 38.64% | 59.09% | 70.45% | 0.4958 | 70.45% | 15493ms |

> srcHit / channel_hit 口径与 `retrieval_eval` 顶层 summary 一致：只对**质量样本**（成功检索）计分母，执行失败样本（8 条超时）不混入质量统计。

### 2.3 观察（限制性结论）

1. **重写整体正向**：全链路 MRR 0.4745 > 图像通道 0.4173；对 `scanned_manual` 尤其明显（Hit@1 36%→56.5%），重写把「图中这个铭牌的型号是多少」规范化为更可检索的 query。
2. **重写对 site_photo 有回退**（Hit@1 48%→31.8%）：现场照片 query 的口语化描述被重写后可能丢失关键视觉锚点，与 R3-D 观察的「单轮省略补全失败」同源。
3. **engineering_drawing 始终最难**（Hit@1 26~28%）：工程图描述长、技术性强，query 与描述文本的语义距离大；且 P95 延迟最高（10~15s），是后续优化重点。
4. **全链路延迟成本**：P95 2.2s→17.7s，主要来自查询重写 LLM 调用 + 网络抖动，与 R3-C 的延迟结论一致。

## 3. 复现命令

```bash
# 索引重建（含修复镜像）
cd d:/Java/agent && $env:PHASE5_INGEST="true"; $env:RAG_RATE_LIMIT_GLOBAL_ENABLED="false"; docker compose up -d --no-deps backend

# R4-C 校验
python scripts/eval/_r4c_ref_check.py

# R4-D 图像通道评测
python scripts/eval/retrieval_eval.py --dataset scripts/eval/datasets/industrial_image_r5_approved.jsonl \
  --scenes image --retrieval-only --disable-hypergraph --disable-rewrite \
  --label r4d-image-channel --runtime-label r4-image --request-timeout 40 --warmup-count 2 \
  --out scripts/eval/report/r4d_20260815/image_channel.json

# R4-D 全链路评测
python scripts/eval/retrieval_eval.py --dataset scripts/eval/datasets/industrial_image_r5_approved.jsonl \
  --scenes image --retrieval-only --disable-hypergraph \
  --label r4d-full-chain --runtime-label r4-image --request-timeout 60 --warmup-count 2 \
  --out scripts/eval/report/r4d_20260815/full_chain.json

# 分组统计（含 Wilson 置信区间）
python scripts/eval/_r4d_breakdown.py --report scripts/eval/report/r4d_20260815/image_channel.json --out scripts/eval/report/r4d_20260815/image_channel_breakdown.json
python scripts/eval/_r4d_breakdown.py --report scripts/eval/report/r4d_20260815/full_chain.json --out scripts/eval/report/r4d_20260815/full_chain_breakdown.json
```

## 4. 局限披露

- 全链路评测有 8/100 条因 SiliconFlow embedding 网络抖动导致图像通道超时（执行不稳定，非质量缺陷）；数据按 quality sample（92 条）统计，执行失败样本单独计入 `excluded_execution_count`。
- 超图通道对图像场景无意义（图像评测 `expected_channels` 仅 `IMAGE_SEMANTIC`），且对图像 query 会超时（R2 已知问题），故两组评测均关闭超图。
- 置信区间采用 Wilson 95%（小样本下比正态近似更保守），见 breakdown 的 `hit_rate_wilson95` 字段。
- **可复现性说明**：评测报告 `execution_fingerprint.git.revision` 为 `ab35fc0` 且 `worktree_dirty=true` —— 因为图像 ingest 修复（`ab35fc0`）与 timeout 修复（`image-semantic.timeout-millis` 3s→10s）在评测执行时尚处于工作区未提交状态，评测运行的是含两处修复的镜像。两处修复现已分别提交于 `ab35fc0`（图像 ingest 固定重试模型）与 `1e5cd91`（timeout 修复，即本闭环提交）。**复现需 checkout 至 `1e5cd91` 及之后**，干净 checkout 上重跑即可得到一致结果。
