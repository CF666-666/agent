# R0-E 受控基线阻塞记录

更新时间：2026-08-12。

## 已完成的可复现性准备

- 评测数据 Schema、数据指纹、运行时指纹和调优/冻结集来源隔离已由 R0-B～R0-D 固化；
- `scripts/eval/archive_baseline.py` 只接受 schema v3 报告，重算分批合并结果后才归档原始报告、汇总报告、镜像 ID、复跑命令和无密钥执行指纹；
- 历史 schema v2 的 `8.2-C/8.2-H` 报告只保留作历史观察，不得作为 R0-E 的受控基线。

## 本次运行阻塞

本机 Docker 服务已启动，后端健康检查 `/api/ragent/health` 返回 `UP`。但 2026-08-12 的容器日志显示百炼 Chat 与 Rerank 请求均返回 `400 Arrearage`（账号欠费/不可用）。全链路检索同时依赖这些模型完成意图分类、关系实体抽取和重排；在此条件下，10 秒受控请求会产生超时或不完整 references，不能被归档为性能或质量基线。

此外，启动期演示 FAQ 重建停留在 `100/210`，与首次评测请求重叠；即使忽略模型错误，该窗口中的索引也不是稳定快照。

## 收口条件

1. 恢复可用的百炼模型额度，或以明确、受控且已记录的替代模型配置启动服务；
2. 等待 `Phase5DataIngestionRunner` 记录“全量数据入库完成”，确认索引稳定；
3. 用 R0-E 固定参数运行 `fact/colloquial/image/relation` 四个 schema v3 原始分片，并由 `merge_eval_reports.py` 合并；
4. 使用 `archive_baseline.py` 归档到 `scripts/eval/report/baselines/<run-id>/`；
5. 仅在归档成功后，把该目录及摘要写入路线图和评测报告。

## 非结论

本记录不报告任何 Hit@K、MRR、延迟或“提升”结论；历史报告中的数字也不因本记录而获得新的简历使用资格。
