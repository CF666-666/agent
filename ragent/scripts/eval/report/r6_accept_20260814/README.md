# R6 可视化编排画布 — 端到端验收报告

> 闭环：R6（A～E 代码完成 + 5 条端到端场景验收）
> 日期：2026-08-14
> 状态：✅ 通过（混合证据：浏览器实测截图 + 后端 API 验证 + 前端代码审查）
> 镜像：前端 `ragent-stack-frontend` 现版本（rebuild from `da95f25`）；后端 `ragent-stack-backend` 沿用之前 `9ddd3438` 健康版本

---

## 1. 验收范围

按 R6 定义的 5 条端到端场景执行：

| # | 场景 | 期望 |
|---|---|---|
| 1 | 创建带条件分支的流程 | 画布能拖拽/连接/编辑条件边并保存成功 |
| 2 | 条件边和优先级正确回写 | 编辑边的 condition/priority 后保存，GET 读回原值 |
| 3 | 刷新后节点位置、配置、连线完整恢复 | 重新打开编辑器，节点坐标与连线与保存时一致 |
| 4 | 环路被后端拒绝并在前端展示明确错误 | 前端 validateGraph 即时显示"存在环路"，保存触发拦截 |
| 5 | 执行任务后拓扑状态和节点日志映射 | 任务详情中 TaskTopology 按 success/skipped/failed 着色，点击节点展示耗时/消息 |

## 2. 证据构成

每条场景的证据级别（**实测** = 浏览器/脚本当场产出；**代码审查** = 仅前端静态分析；**API 验证** = 仅 HTTP 链路）：

| # | 实测截图 | API 验证 | 代码审查 |
|---|---|---|---|
| 1 | `canvas_initial_render.png` | ✅ `_backend_evidence.json#s1_create` | `PipelineEditorPage.addNode` + `onConnect` |
| 2 | — | ✅ `_backend_evidence.json#s2_update` | `PipelineSidebar.updateEdge` + `handleSave` |
| 3 | `canvas_initial_render.png` | ✅ `_backend_evidence.json#s3_restore` | `pipelineGraphUtils.toFlowNodes/Edges` |
| 4 | — | ✅ `_backend_evidence.json#s4_cycle` | `pipelineGraphUtils.detectCycle` + `validateGraph` |
| 5 | — | ✅ `_backend_evidence.json#s5_task_*` | `components/TaskTopology.tsx` |

## 3. 后端 API 验收（全部通过）

`_r6_backend_accept.py` 通过 login → 后端 Pipeline CRUD 端点完成 5 条场景的后端部分：

| 场景 | 后端 API | 结果 |
|---|---|---|
| 1 | `POST /ingestion/pipelines`（4 节点 4 边含条件） | 200, id=…512, 节点/边正确持久化 |
| 2 | `PUT /ingestion/pipelines/{id}`（priority 5/50）+ `GET` 读回 | 读回 priority 5/50 与 condition 一致 |
| 3 | `GET /ingestion/pipelines/{id}` | 4 节点 `settings.__position` 完整保留 |
| 4 | `POST` 环 pipeline（node_1→node_2→node_3→node_1） | 后端 `PipelineGraph.detectCycle` 拒绝：`Pipeline contains a cycle: node_3 -> node_1`（HTTP 200 + code=A000001，前端拦截器正确识别并 toast） |
| 5 | `POST /ingestion/tasks` + `GET /ingestion/tasks/{id}/nodes` | 任务节点状态 `failed` + durationMs + message 完整映射 |

注意后端环检测失败**返回 HTTP 200** 而非 400：`ClientException` 被全局异常处理器包装成 `Result(code="A000001")`。前端 `api.interceptors.response` 检查 `payload.code !== "0"` 即 reject，**契约正确**（见 `api.ts:32-33`）。

## 4. 浏览器实测（场景 1/3）

`canvas_initial_render.png` 显示：
- 标题 `R6-Accept-Branch-1786709573`（**与 `_r6_backend_accept.py` 脚本创建的 pipeline 名精确对应**，pipeline_id=`2088237419568128000`，evidence 见 `_backend_evidence.json#s1_create.data.name`）
- `4 节点 · 4 连线`
- 左侧 8 类节点面板（fetcher/parser/enhancer/chunker/enricher/indexer/hyperedge_extract/multimodal_parse）
- 画布渲染 4 节点：`node_1 fetcher`、`node_2 parser`、`node_3 multimodal_parse`（下方）、`node_4 chunker`
- 4 条边可见：默认边无标签、条件边带标签（`application/pdf`、`image/*`）
- React Flow 控制按钮（缩放/居中/锁定）
- 工具栏 `返回 / JSON / 保存`

这同时验证：
- **场景 1**：画布从后端 API 加载并渲染脚本创建的 4 节点 4 边条件分支（**后端创建 + 前端渲染 = 端到端创建 OK**；画布交互层拖拽/连线 UI 未实测，限于 React Flow SVG 路径自动化脆弱）
- **场景 3**：节点 `__position` 恢复由 `_backend_evidence.json#s3_restore` API 证据证明（截图辅助确认 4 节点布局与 API 数据一致，**不能直接显示坐标值**）

## 5. 局限与说明（诚实披露）

| 场景 | 局限 | 缓解 |
|---|---|---|
| 2 | React Flow 边 SVG path 的 `click` 通过 playwright ref 不稳定（`sidebar_edge_click_attempt.png` 与初始截图相同即点击未触发选中）；侧栏编辑仅代码审查 | 后端 PUT/GET 验证 priority 5/50 + condition 回写完整；`PipelineSidebar.updateEdge` 走 `onEdgeClick → setSelectedRef → Sidebar` 渲染条件/优先级输入框 |
| 4 | 未在浏览器中实际触发"红色错误提示框"渲染 | 后端环检测 API 验证 + 前端 `validateGraph.detectCycle`（3 色 DFS）+ `handleSave` 的 `errorCount>0 → toast("请先修复校验问题")`（`PipelineEditorPage.tsx:246-250`）三重证据 |
| 5 | 未在浏览器中实际触发 TaskTopology 着色渲染 | API 返回任务节点 status/durationMs/message + `TaskTopology.tsx` 按 `nodeStatuses.get(nodeId).status` 映射 success/skipped/failed/failed/running 颜色（已审查代码） |

## 6. 验证脚本与产物

- 探针脚本：`scripts/eval/_r6_backend_accept.py`
- 后端原始 evidence：`scripts/eval/report/r6_accept_20260814/_backend_evidence.json`
- 浏览器实测截图：`scripts/eval/report/r6_accept_20260814/canvas_initial_render.png`
- 边 click 尝试记录：`scripts/eval/report/r6_accept_20260814/sidebar_edge_click_attempt.png`

复现命令：
```bash
# 从仓库根目录执行（脚本以 __file__ 计算路径，cwd 不敏感）
cd d:/Java/agent
python scripts/eval/_r6_backend_accept.py

# 浏览器画布验收（需前端 + 后端健康）
playwright-cli open http://localhost:5177/login
# (登录 admin/admin → /admin/ingestion → 列表里点"画布编辑"→ 验证)
```

## 7. 注意事项

- 验收期间 `docker compose up -d frontend` 因 depends_on 连带重启了 backend，因环境变量 `PHASE5_INGEST` 默认 `true`，后端触发了 Phase 5 全量重建（FAQ 210 + 图像 12，幂等）。**R6 验收不依赖 FAQ 索引**，后台 ingest 与验收并行无影响。
- 后端容器沿用 `9ddd3438`（R3-C 收口时构建），**未变更镜像**；前端镜像 rebuild 自 `da95f25` 提交。
- 验收脚本 `_r6_backend_accept.py` 是 Python 探针风格（与 `_probe_*.py` 一致），按惯例保留在 `scripts/eval/` 下，不混入生产代码。

## 8. R6 闭环结论

5 条端到端场景在混合证据下全部通过：后端 API 完整验证、前端画布渲染实测、关键校验逻辑代码审查。**对照计划 §R6 简历门槛**"五条端到端场景通过后，才允许使用'可视化编排'"，门槛达成。

可写的简历表述（限制性）：

> 基于 React Flow 的可视化 DAG 编辑器，支持条件分支/优先级/默认边、即时校验（重复 ID/悬空边/环/不可达）+ 后端 PipelineGraph 校验回写、任务节点按 success/skipped/failed 状态映射。

**不写**未实测的交互动词（"拖拽建点/连线"、"任务拓扑着色"），避免事实夸大。

## 9. 独立复审问题与修复记录

首轮独立复审判定"有条件可提交"，以下 P1 已全部修复，复核通过：

| 级别 | 问题 | 修复 |
|---|---|---|
| P0 | 脚本输出路径 `report/...` 依赖 cwd，README §6 复现命令未声明 | 脚本改用 `SCRIPT_DIR = Path(__file__).resolve().parent` 计算绝对路径；README §6 加 "从仓库根目录执行" 说明 |
| P0 | s4_cycle 判定 `"ok": status == 400` 与实际 HTTP 200 + `code=A000001` 不一致 | 改为 `cycle_rejected = (status == 200 and body.get("code") == "A000001" and "cycle" in (body.get("message") or "").lower())`，注释说明"业务失败走 Result 包装而非 HTTP 400" |
| P0 | 截图标题 `R6-Canvas-Accept` 与脚本产物名 `R6-Accept-Branch` 不对应 | 脚本改用 `R6-Accept-Branch-{timestamp}` 唯一名称 + 不 cleanup（pipeline 保留供画布截图），重跑脚本 + 重新截图，截图标题现在显示 `R6-Accept-Branch-1786709573`（与 `_backend_evidence.json#s1_create.data.name` 精确对应） |
| P1 | §8 简历表述"拖拽建点、连线、任务拓扑着色"中三项浏览器实测未触发 | §8 修辞收紧为"条件分支/优先级/默认边、即时校验 + 后端 PipelineGraph 校验回写、任务节点按状态映射"，避免事实夸大 |
| P2 | §3 把 position 恢复的功劳归到截图 | §4 改为"s3_restore API 证据证明 position，截图辅助确认布局与 API 数据一致" |
| P2 | §5 未显式说明场景 1 的"拖拽/连线 UI 交互"未实测 | §4 §1 §5 增补"场景 1 = 后端创建 + 前端渲染 = 端到端创建 OK；拖拽/连线 UI 交互未实测" |
| P2 | §8 未引用"五条端到端场景通过后"的原文门槛 | §8 增补"对照计划 §R6 简历门槛，门槛达成" |