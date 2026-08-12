# 开发交接说明（2026-08-13）

## 目的

本说明用于在另一台电脑克隆仓库后继续开发，记录当前分支、已提交能力、未完成工作及本次随仓库保留的本地材料。

## 获取当前状态

```bash
git clone git@github.com:CF666-666/agent.git
cd agent/ragent
git checkout codex/phase1-durable-ingestion
git pull --ff-only
```

本次交接提交之后，分支将包含截至 2026-08-13 的全部可共享源码、文档、评测脚本与历史评测报告；本机密钥、依赖缓存和临时诊断产物不纳入版本控制。

## 已完成的关键节点

- Phase 1：可恢复文档入库编排引擎已收口。
- Phase 2A：超边入库闭环已收口。
- R0-A ～ R0-D：简历证据化的数据契约、执行指纹与冻结集隔离已完成。
- R0-E-1：统一检索 deadline、超图通道预算、客户端取消传播、`retrieval_status` SSE 与评测执行状态分离已完成；实现提交为 `a6652bc`。

## 当前未完成项

R0-E 尚未整体结束。稳定索引已实测 FAQ `210/210/210`、图像 12 条；下一步是在同一运行配置和数据集指纹下完成 schema v3 的事实、口语、图纸、关系四类真实评测，合并校验、归档，并据此更新评测报告与简历指标。

详细任务与证据见：

- `docs/upgrade/dev-roadmap.md`
- `docs/upgrade/r0-e-baseline-blocker.md`
- `docs/upgrade/r0-e-1-retrieval-reliability.md`
- `scripts/eval/report/`

## 本次纳入的本地材料

- `scripts/eval/report/`：历史探针、分批报告与 R0-E 原始报告，供问题定位和后续复跑比较。
- `docs/upgrade/phase0-baseline-report.md`：阶段 0 的历史基线说明。
- `evaluation-expansion-todo.md`：评测集扩容的待办参考。
- `frontend/*test*.mjs`、`frontend/verify_strong.mjs`：前端 Markdown/展示验证脚本。

这些报告含有不同的运行条件、开关和预算配置。除非按 schema v3 指纹校验后完成受控合并，否则不得直接用其中的 Hit@K、MRR 或延迟数字更新简历。

## 有意不纳入仓库的本地文件

- `.env`、`frontend/.env`：可能包含 API Key 或机器相关配置。
- `tessdata/`：Tesseract 语言数据，体积较大，可在新机器按部署文档下载或挂载。
- `scripts/`（仓库根目录）：一次性诊断脚本、HTTP/SSE dump、抓取产物和打包文件。
- `scripts/eval/__pycache__/`：Python 字节码缓存。

新机器需自行配置 `SILICONFLOW_API_KEY` / `BAILIAN_API_KEY` 等环境变量；密钥不应提交到 Git。

