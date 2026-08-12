# R0-E 受控基线阻塞记录

更新时间：2026-08-12。

## 已完成的可复现性准备

- 评测数据 Schema、数据指纹、运行时指纹和调优/冻结集来源隔离已由 R0-B～R0-D 固化；
- `scripts/eval/archive_baseline.py` 只接受 schema v3 报告，重算分批合并结果后才归档原始报告、汇总报告、镜像 ID、复跑命令和无密钥执行指纹；
- 历史 schema v2 的 `8.2-C/8.2-H` 报告只保留作历史观察，不得作为 R0-E 的受控基线。

## 本次运行状态

默认模型路由已切换为 SiliconFlow，百炼适配器保留为可选配置，不再构成当前 R0-E 的阻塞条件。2026-08-12 的直连探针已确认 `Qwen/Qwen3-Embedding-8B` 可返回 4096 维向量。

此前的启动期 FAQ 重建存在失效风险：单条 embedding 超时会被吞掉，随后仍继续图像入库并错误记录“全量完成”，导致仅有部分 FAQ 的索引被误认为稳定快照。该行为已修复为受控失败闭环：固定 `qwen-emb-8b`、每条最多 3 次远程调用，仅对网络/429/5xx 做退避重试；任何失败或计数不一致都会清理部分 FAQ、跳过图像入库并让启动失败。只有合法 FAQ 输入数、成功 embedding 数和 Milvus 实际记录数一致，且图像入库完成后才记录全量完成。

当前剩余工作是使用修复后的镜像完成一次完整启动，并在确认 FAQ 计数一致后运行四类 schema v3 评测；此前 `50/210`、`100/210` 或其他部分入库记录均不得归档或引用。

## 收口条件

1. 以已记录的 SiliconFlow 配置启动服务，等待 `Phase5DataIngestionRunner` 记录全量完成，并确认 FAQ 三方计数一致；
3. 用 R0-E 固定参数运行 `fact/colloquial/image/relation` 四个 schema v3 原始分片，并由 `merge_eval_reports.py` 合并；
4. 使用 `archive_baseline.py` 归档到 `scripts/eval/report/baselines/<run-id>/`；
5. 仅在归档成功后，把该目录及摘要写入路线图和评测报告。

## 非结论

本记录不报告任何 Hit@K、MRR、延迟或“提升”结论；历史报告中的数字也不因本记录而获得新的简历使用资格。
