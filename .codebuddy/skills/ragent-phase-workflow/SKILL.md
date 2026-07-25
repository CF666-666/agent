---
name: ragent-phase-workflow
description: >
  Ragent 项目 Phase 级别开发的编排工作流。当用户提到"开始 Phase X"、
  "进入 Phase X"、"开发 Phase X"、或从上一个 Phase 收尾后自然进入
  下一个 Phase 时触发。该 skill 负责：Phase 整体方案讨论→逐闭环开发
  （每个闭环走 ragent-dev-workflow + grill-me + code-review）
  →Phase 整体 review→收尾。不要和 ragent-dev-workflow（单闭环级别）混淆。
---

# Ragent Phase 开发编排工作流

> **定位**：Phase 级别的编排器。管理一个 Phase 从方案到收尾的完整生命周期。
> **单闭环开发**由 `ragent-dev-workflow` skill 负责。
> **设计追问**由 `grill-me` / `batch-grill-me` skill 负责。
> **代码审查**由 `requesting-code-review` skill 负责。
> 本 skill 负责在正确的时间点加载正确的 skill、串起整个流程。

---

## 依赖的 Skill

| Skill | 用途 | 加载时机 |
|-------|------|---------|
| `ragent-dev-workflow` | 单闭环 5 步编码流程 | Phase Kickoff + 每个闭环开始前 |
| `batch-grill-me` | Phase 级设计追问（每轮问全部 frontier 问题） | Phase 方案确认后 |
| `grill-me` | 闭环级设计追问（一次一问，决策树遍历） | 每个闭环的 Step 1 后 |
| `requesting-code-review` | 代码审查 | 每个闭环收尾前 + Phase 全部闭环完成后 |

---

## Phase 开发全流程

```
Step 1: Phase Kickoff     → 方案讨论
    ↓
Step 2: Phase Grill        → batch-grill-me 追问整体方案
    ↓
Step 3: Closure Loop       → 逐闭环开发（见下方子流程）
    ↓  (循环直到所有闭环完成)
Step 4: Phase Code Review  → requesting-code-review 审查整个 Phase
    ↓
Step 5: Phase Finalize     → 更新 roadmap + git commit
```

---

## Step 1: Phase Kickoff — 方案讨论

### 1.1 加载上下文

使用 `use_skill("ragent-dev-workflow")` 加载单闭环工作流规范。

然后读取以下文件获取 Phase 全貌：

| 文件 | 读取目的 |
|------|---------|
| `DEVELOPMENT.md` | 扩展点机制、禁止行为、技术栈约束 |
| `ragent/docs/upgrade/dev-roadmap.md` | 本 Phase 的全部闭环列表、依赖关系、预计工时 |
| `ragent/docs/upgrade/upgrade-plan.md` | 本 Phase 的架构设计参考 |

### 1.2 输出 Phase 整体方案

```
Phase X 整体方案
═══════════════════════════════════════

A. Phase 目标（一段话）

B. 闭环拆分（从 roadmap 中提取）
   │ 闭环 │ 类型 │ 产出文件数 │ 依赖          │ 预计工时 │
   │ 1    │ T1   │ 2         │ 无            │ 0.5h    │
   │ 2    │ T3   │ 1         │ 闭环1         │ 4h      │
   │ ...  │      │           │               │         │

C. 关键依赖链
   闭环1(零依赖) → 闭环2(依赖1) → 闭环3(依赖2)
                               → 闭环4(依赖2)  ← 与3可并行

D. 新增包/目录一览

E. 风险识别
```

### 1.3 确认门禁

用户说"确认"/"通过"/"开始" → 进入 Step 2。

---

## Step 2: Phase Grill — batch-grill-me

用户确认方案后，**不立即开始编码**。先对 Phase 整体设计做一轮追问。

### 2.1 加载 grill skill

```bash
use_skill("batch-grill-me")
```

`batch-grill-me` 的行为：
- 构建一棵 **design tree**：每个设计决策分支出依赖它的子决策
- **Frontier** = 所有前置依赖已解决、现在就能问的问题
- 每轮一次性问完整个 frontier，给每个问题编号 + 推荐答案
- 用户回答后重新计算 frontier，继续下一轮
- Frontier 为空时结束——所有分支都被走过，没有默默留下的假设

### 2.2 Grill 覆盖维度（引导 design tree 结构）

以下 5 个维度作为设计树的顶层分支，确保不遗漏：

| 维度 | 检查内容 |
|------|---------|
| **闭环拆分** | 粒度是否合理？有没有可合并/可拆分的？大闭环＞200 行考虑拆 |
| **依赖顺序** | 依赖链有没有循环？有没有隐含依赖被忽略？（接口→实现→集成） |
| **基础设施复用** | 有没有在重复造已有设施的轮子？ChatClient/LLMService/EmbeddingService/Milvus 是否可直接注入？ |
| **扩展点合规** | 新增代码是否全部通过扩展接口接入？（SearchChannel/PostProcessor/IngestionNode） |
| **自测策略** | 每个闭环的自测标准是否合理？（纯定义型只编译、实现型跑测试、集成型确认 Bean） |

### 2.3 完成标准

`batch-grill-me` 的 frontier 为空（所有问题已问完、所有决策已定调）→ 进入 Step 3。

---

## Step 3: Closure Loop — 逐闭环开发

按闭环顺序（1 → N）逐个执行。每个闭环走以下子流程：

```
┌────────────────────────────────────────────────┐
│  use_skill("ragent-dev-workflow")               │
│    ↓                                            │
│  Step 1: 闭环方案设计                            │
│    ↓                                            │
│  use_skill("grill-me")  ← 追问闭环方案            │
│    ↓  (决策树遍历完成)                             │
│  ragent-dev-workflow Step 2-4: 编码+自测+展示     │
│    ↓                                            │
│  use_skill("requesting-code-review")  ← 审查     │
│    ↓  (有问题 → 修复 → 重审，直到 0 问题)           │
│  ragent-dev-workflow Step 5: 收尾（roapmap+commit）│
│    ↓                                            │
│  下一闭环 / Step 4                                │
└────────────────────────────────────────────────┘
```

### 3.1 闭环方案

走 `ragent-dev-workflow` 的 Step 1，按闭环类型（T1-T5）选模板，输出完整设计方案。

### 3.2 闭环 Grill

用户确认方案后，加载 `grill-me`：

```bash
use_skill("grill-me")
```

`grill-me` 的行为：
- 一次只问一个问题，附推荐答案
- 沿决策树一个分支一个分支走下去
- codebase 能回答的问题自行探索，不问你
- 直到达成共同理解后才停止

对比 Step 2 的 `batch-grill-me`：Phase 级用 batch（宽话题适合并行扫），闭环级用 `grill-me`（窄话题适合逐项深挖）。

### 3.3 编码 + 自测 + 展示

走 `ragent-dev-workflow` 的 Step 2-4。

### 3.4 Code Review

闭环收尾前，加载 `requesting-code-review`：

```bash
use_skill("requesting-code-review")
```

Review 范围：**本闭环新增/修改的全部文件**。

发现问题 → 修复 → 重新 review → 直到 0 问题 → 进入收尾。

### 3.5 收尾

走 `ragent-dev-workflow` 的 Step 5（更新 roadmap + git commit）。

输出：`闭环 X 完成。产出 N 个文件。Review 通过。下一个是闭环 X+1：[描述]`

---

## Step 4: Phase Code Review

所有闭环完成后，对整个 Phase 做一次整体 review。

```bash
use_skill("requesting-code-review")
```

### Review 重点（与单闭环 review 不同）

| 维度 | 检查内容 |
|------|---------|
| **跨闭环接口一致** | 闭环 1 定义的接口，闭环 3 的实现是否完全匹配？签章有无漂移？ |
| **依赖链完整** | 闭环间依赖是否都正确注入？有无遗漏的 Bean 注册？ |
| **包结构整洁** | 所有新增文件是否在规划的包路径下？ |
| **命名冲突** | 新增类名/Bean 名/NodeType/SearchChannelType 是否与已有代码冲突？ |
| **编码规范统一** | 是否所有类都遵循 Lombok + Slf4j + RequiredArgsConstructor？ |

发现问题 → 逐个修复 → 重新 review → 直到 0 问题。

---

## Step 5: Phase Finalize

### 5.1 更新 Phase 进度

在 `ragent/docs/upgrade/dev-roadmap.md` 底部进度表中，将本 Phase 行改为 `✅ 完成`。

### 5.2 Git 提交

```bash
git add .
git commit -m "feat(phaseX): Phase X 完成 - [一句话总结]"
git push origin main
```

### 5.3 完成确认

```
Phase X 完成 ✅
═══════════════════════════════════════
闭环数: N    产出文件: M    审查通过: ✅
下一 Phase: Phase X+1 — [描述]
```

---

## 全局禁止行为

- ❌ Phase Kickoff 后跳过 `batch-grill-me` 直接编码
- ❌ 闭环编码前跳过 `grill-me` 直接写代码
- ❌ 闭环收尾前跳过 `requesting-code-review`
- ❌ 所有闭环做完后跳过 Phase 整体 review
- ❌ 用户未确认 Phase 方案前开始任何闭环
- ❌ 修改已有核心逻辑（Pipeline/检索/路由/Rerank）
- ❌ 试图在本 skill 中重新实现 grill-me / batch-grill-me 的逻辑——加载对应的 skill 即可

---

## 关键文件索引

| 用途 | 路径 |
|------|------|
| 开发规范 | `DEVELOPMENT.md` |
| 路线图 & 进度 | `ragent/docs/upgrade/dev-roadmap.md` |
| 技术方案 | `ragent/docs/upgrade/upgrade-plan.md` |
| 单闭环开发 skill | `.codebuddy/skills/ragent-dev-workflow/SKILL.md` |
