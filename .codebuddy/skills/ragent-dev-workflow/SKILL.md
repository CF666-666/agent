---
name: ragent-dev-workflow
description: >
  Ragent 项目升级开发的强制工作流。当用户提到"开始编码"、"进入 Phase X"、
  "开发第X个闭环"、"实现XX模块"、"按 roadmap 开发"、或任何涉及 ragent 
  项目 Java 代码编写/修改的请求时，必须先读取 DEVELOPMENT.md 了解项目背景，
  然后按照 5 步工作流（方案→编码→自测→review→收尾）进行最小化闭环开发。
  每个闭环完成后必须更新 dev-roadmap.md。
---

# Ragent 项目开发工作流

> **适用于所有 Phase 的所有闭环**。5 步流程不变，但每步的具体要求会根据闭环类型自动适配。

---

## Step 0：上下文加载（每次编码前必须执行）

以下 3 个文件必须在进入 Step 1 之前全部读完：

| # | 文件 | 目的 |
|:--:|------|------|
| 1 | `DEVELOPMENT.md`（仓库根目录） | 技术栈约束、包路径规范、禁止行为、扩展点机制 |
| 2 | `ragent/docs/upgrade/dev-roadmap.md` | 确认当前 Phase 进度、闭环间依赖关系 |
| 3 | `ragent/docs/upgrade/upgrade-plan.md` | 架构设计、类签名参考、入库流程 |

**输出**：当前 Phase + 闭环编号 + 前置依赖状态。

---

## 闭环类型判定（进入 Step 1 前先分类）

对照下表，判定本闭环属于哪种类型。**类型决定了 Step 1 方案模板中哪些项必填、哪些可跳过。**

| 编号 | 类型名 | 典型特征 | 真实例子 |
|:--:|------|------|------|
| T1 | **纯定义** | 只出接口 + 实体/DTO，无实现逻辑，无外部依赖，不调 Spring Bean | Phase 3 闭环 1（HyperEdge + IndustrialHyperGraph 接口）、Phase 1 闭环 2（MultimodalDocumentParser 接口 + ParseResult DTO） |
| T2 | **配置变更** | 只改 pom.xml / application.yaml / Milvus 初始化 / 环境变量 | Phase 1 闭环 1（pom.xml 加依赖）、Phase 2 闭环 1（新建 Milvus Collection） |
| T3 | **实现类（有外部依赖）** | 新增 Service/Component/Parser 实现类，调第三方库或远程 API | Phase 1 闭环 3-5（PdfBoxParser / Tess4JParser / QwenVLImageParser）、Phase 3 闭环 4-5（HyperEdgeExtractor / EntityExtractor） |
| T4 | **集成接入** | 实现已有框架接口（SearchChannel / IngestionNode / PostProcessor），挂载到已有链路 | Phase 1 闭环 7（MultimodalDocumentParserNode）、Phase 2 闭环 3（ImageSearchChannel）、Phase 3 闭环 6（HyperGraphSearchChannel） |
| T5 | **非 Java 产出** | 数据生成脚本 / 前端 TSX / Markdown 文档 / Python 脚本 | Phase 5（数据生成）、Phase 6（前端增强）、Phase 7（文档整理） |

> 一个闭环可能混合多种类型（如"接口定义 + 配置变更"），此时各类型的要求取**并集**。

---

## Step 1：方案设计（按闭环类型选模板）

**T1-T5 共用（必须覆盖）**：

```
A. 产出清单
┌──────┬──────────────────────────────────────┬──────────────┐
│ 状态  │ 文件（完整路径）                        │ 说明          │
├──────┼──────────────────────────────────────┼──────────────┤
│ 新增  │ rag/core/hypergraph/HyperEdge.java   │ 超边实体类     │
│ 修改  │ ragent/bootstrap/pom.xml             │ 加 JGraphT 依赖│
└──────┴──────────────────────────────────────┴──────────────┘

B. 依赖关系（本闭环依赖哪些已有模块 / 前置闭环）
  - 依赖 Phase 0 B1（编译通过确认）
  - 不依赖任何新建模块（本闭环是最底层）

G. 自测标准（本闭环怎么算"通过"）
  - T1/T2/T3/T4: mvnw compile -pl bootstrap -am -DskipTests → BUILD SUCCESS
  - T3 额外: 单元测试通过（列出要测的场景数）
  - T5: 脚本能跑 / npm run build 成功 / 文件内容合规
```

**T1（纯定义）额外覆盖**：

```
C. 类签名 + 设计理由
  public interface IndustrialHyperGraph {
      List<HyperEdge> extractHyperedges(String docText);
      // ↑ 为什么返回 List 而不是 Set？超边可能有重复抽取，保留顺序供去重
      void addHyperedges(List<HyperEdge> edges);
      List<SubgraphMatchResult> matchSubgraph(Set<String> entities, int maxEdges);
      // ↑ 为什么返回 SubgraphMatchResult 而不是裸 HyperEdge？
      //   闭环 3 的 SearchChannel 需要 matchCount 排序，丢了要重算
  }

D. 关键设计决策（每个决策附"为什么不用替代方案"）
  - 字段设计选 5 核心 + extendedEntities，不用 Map<String,String>
    理由: Map 的 key 漂移导致 expandToText 模板失效
  - expandToText 用模板，不调 LLM
    理由: 0 API 消耗，结构化事实对 Embedding 更友好
```

**T2（配置变更）额外覆盖**：

```
E. 兼容性检查
  - 新增依赖的版本是否与已有 pom 中其他依赖冲突？
  - 新 Milvus Collection 的维度是否与 Embedding 模型一致？
  - 新配置项是否会覆盖已有配置？
```

**T3（实现类）额外覆盖**：

```
C. 类签名 + 设计理由（同 T1，但重点在依赖注入方式）
D. 关键设计决策（同 T1）
E. 异常路径分析（每个 public 方法至少 2 个异常场景）
  parse(File file, FileType type):
    - file 不存在 → 抛出 FileNotFoundException，上游捕获
    - file 为空目录 → 返回空 ParseResult，log.warn
    - 外部 API 超时 → 重试 2 次后抛 RuntimeException，由上层熔断处理
  - 资源释放: try-with-resources 包裹所有 IO 操作
  - 并发安全: 该 Parser 是否为无状态 Bean？如果是，标注 @ThreadSafe
```

**T4（集成接入）额外覆盖**：

```
F. 接口契约对照
  - 实现的接口是什么？每个方法的契约要求是什么？
  - SearchChannel.getChannelType(): 返回的 SearchChannelType 不能与已有撞名
  - IngestionNode.getNodeType(): 返回的字符串要全局唯一
  - PostProcessor.getOrder(): 确认插入顺序是否合理（如融合要在 Rerank 之前）
  - 是否需要新的 Milvus Collection？如果需要，回落 T2 兼容性检查
```

**T5（非 Java 产出）额外覆盖**：

```
- 如果是数据生成：数据格式（jsonl/csv）、字段 schema、预计条数
- 如果是前端：涉及哪些组件文件、与后端 API 的对接点
- 如果是文档：文档结构大纲
- 自测方式：npm run build / python script.py --dry-run / 人工 review 清单
```

### 确认门禁

**用户说"可以"/"通过"/"开始编码"等明确确认词后，才能进入 Step 2。** 如果提了设计追问，补充后再次等待确认。

---

## Step 2：编码（按类型分流）

### T1-T4（Java 编码）

**编码顺序**：被依赖的先写，零依赖的最先写。

```
正确: HyperEdge → IndustrialHyperGraph → Impl
错误: 先写 Impl，发现接口还没定义，编译不过
```

**编码规范（全类型通用）**：

| 规范 | 说明 |
|------|------|
| 包路径 | `com.nageoffer.ai.ragent.*`，多模态进 `multimodal/`，超图进 `rag/core/hypergraph/` |
| Lombok | 实体类 `@Data @Builder @NoArgsConstructor @AllArgsConstructor` |
| 日志 | 实现类 `@Slf4j`，关键路径 `log.debug`，异常 `log.warn`（不吞异常） |
| 空值防御 | 集合返回空集合不返回 null；`Objects.requireNonNull` 防御关键参数 |
| 注入 | 构造器注入（`@RequiredArgsConstructor` + `private final`），不用 `@Autowired` 字段注入 |
| 依赖声明 | Maven 依赖只在 Step 1 方案中声明过的才加 |
| **禁止** | 不修改已有核心逻辑（Pipeline/检索/路由/Rerank），只实现扩展接口 |

**编译检查点**：每写完一个独立编译单元，立刻跑 `mvnw compile -pl bootstrap -am -DskipTests`。不过不往下写。

### T5（非 Java 产出）

按 Step 1 方案中约定的格式产出，不适用 Java 规范。如果是前端代码，遵守已有 `tsx` 文件的代码风格。

---

## Step 3：自测

### T1-T4

```
1. 编译验证:
   cd ragent && ./mvnw compile -pl bootstrap -am -DskipTests
   → 必须 BUILD SUCCESS

2. 逻辑验证（按闭环类型适配）:
   T1（纯定义）: 编译通过即通过，无额外验证
   T2（配置变更）: 编译通过 + 检查 pom 无重复依赖 + Milvus 连接正常
   T3（实现类）: 编译通过 + 按 Step 1 异常路径清单逐条验证空值/边界/超时
   T4（集成接入）: 编译通过 + 确认 Bean 被 Spring 扫描到 + 接口契约合规

3. 单元测试（如有）:
   cd ragent && ./mvnw test -pl bootstrap -am
```

### T5

按 Step 1 约定的自测方式验证。

**自测不通过 → 修 → 重新自测 → 直到通过 → 才能进 Step 4。**

---

## Step 4：Review（按类型展示）

### T1-T4 展示内容

```
A. 编译结果: BUILD SUCCESS / FAILURE

B. 改动文件清单:
   │ 状态  │ 文件                                  │
   │ 新增  │ rag/core/hypergraph/HyperEdge.java    │
   │ 新增  │ rag/core/hypergraph/IndustrialHyperGraph.java │

C. 方案 vs 实际对照:
   方案声明 X 个文件 → 实际 X 个 ✓（有差异则解释）

D. 已知局限（本闭环不做、留给后续的事）:
   - 闭环 1 只有接口和实体，无实现逻辑
   - 倒排索引构建在闭环 3 的 Impl 中实现
```

### T5 展示内容

```
A. 产出物清单（文件/截图/运行结果）

B. 方案 vs 实际对照
```

### Review 门禁

- 用户说"通过"/"OK"/"没问题" → Step 5
- 用户提修改意见 → 回对应 Step，改完重新走到这里

---

## Step 5：收尾（全类型通用）

### 5.1 更新 roadmap

在 `ragent/docs/upgrade/dev-roadmap.md` 中，找到本闭环对应的任务行，将状态 `⬜` 改为 `✅`，追加完成日期。

### 5.2 更新 Phase 进度表

如果本闭环是当前 Phase 的最后一个任务，将 roadmap 底部进度表中对应 Phase 行改为 `✅ 完成`。

### 5.3 Git 提交

```bash
git add .
git commit -m "feat(phaseX): 闭环Y - 具体做了什么"
git push origin main
```

commit message 格式：
- `feat(phase1): 闭环1 - pom.xml 加 PDFBox/Tess4J/JavaCV/JGraphT 依赖`
- `feat(phase3): 闭环1 - 超边实体 + 引擎接口定义`
- `feat(phase5): 闭环1 - LLM 批量生成 200 条工业 FAQ`
- `chore(phase7): 闭环1 - 更新 README 多模态架构图`

### 5.4 完成确认

`闭环 X（Phase Y, T{类型}）完成。产出 N 个文件。下一个是闭环 X+1：[任务描述]`

---

## 全局禁止行为（全类型通用）

- ❌ 跳过 Step 1 设计方案直接写代码
- ❌ 一次写多个闭环的代码
- ❌ 跳过自测直接给用户看结果
- ❌ 修改已有核心逻辑（Pipeline/检索/路由/Rerank）
- ❌ 用户 review 未通过就开始下一闭环
- ❌ 引入方案中未声明的 Maven 依赖
- ❌ commit message 使用 "update" / "fix" / "WIP" 等模糊描述
- ❌ 忽略异常路径分析（T3/T4 每个方法必须考虑空值/边界/超时）

---

## 关键文件索引

| 用途 | 路径 |
|------|------|
| 开发规范 | `DEVELOPMENT.md`（仓库根目录） |
| 路线图 & 进度 | `ragent/docs/upgrade/dev-roadmap.md` |
| 技术方案 | `ragent/docs/upgrade/upgrade-plan.md` |
| 源码排查 | `ragent/docs/upgrade/source-checklist.md` |
| 主 POM | `ragent/bootstrap/pom.xml` |
| 应用配置 | `ragent/bootstrap/src/main/resources/application.yaml` |
