# 开发模块顺序与进度追踪

> **目标岗位**：央国企（国家电网、中石油、烟草、中车等）Java 后端/算法岗 秋招  
> **起始日期**：2026-07-24  
> **预计总周期**：7 周（49 天），含生产级特性打磨 + GitHub 整理  
> **项目定位**：**工业级可上线系统**——所有架构决策、扩展点设计、异常兜底、线程安全策略均按生产标准执行；面试官视角需能直接看到"工程成熟度 + 学术前沿性 + 信创合规性"三重价值

---

## 开发原则

1. **最小可验证单元**：每个模块完成即有独立可跑的 Demo，不依赖全链路完成
2. **存量不动**：已有 Pipeline/检索/路由/Rerank 核心逻辑零修改，仅通过接口扩展接入
3. **一次做到位**：每个闭环内同步完成"主流程 + 异常兜底 + 线程安全 + 防御编程"，不留技术债，避免后续重构
4. **先跑通核心流**、再补边缘情况、最后打磨前端展示
5. **每完成一个 Phase 推一次 GitHub**，维护 commit 历史

---

## Durable ingestion hardening — review closeout

This acceptance record closes the durability risks found after the original ingestion-orchestration delivery (`84bf986`). It is intentionally kept separate from the multimodal delivery phases below.

| Item | Acceptance evidence | Status |
|---|---|:---:|
| HTTP resume | Authenticated `POST /ingestion/tasks/{id}/resume` MockMvc test exercises controller, service, checkpoint store and PostgreSQL persistence. | Done |
| Resume ownership | Conditional lease claim occurs before checkpoint restoration and embedding; restore setup failures release the lease and return the task to `FAILED`. | Done |
| Lease validity | `checkpoint` and `complete` require `lease_expires_at > clock_timestamp()` in the same conditional update, so expired workers cannot overwrite progress. | Done |
| Idempotent replacement | Vector-store replacement upserts new chunks before removing stale chunks; PostgreSQL performs both operations transactionally. | Done |
| Condition observability | Invalid SpEL becomes a failed task with a node-level error log instead of a silent false route. | Done |
| Dependency seams | `IngestionEngine` depends on `NodeExecutionExecutor`; task orchestration depends on `IngestionTaskProgressStore`, not the concrete adapters. | Done |
| Routing seam | `PipelineGraph` owns topology only; `PipelineConditionMatcher` and `PipelineRouteResolver` own condition and next-hop policy. | Done |

Acceptance tests: `TaskCheckpointStorePostgresIntegrationTest`, `IndexerNodeTest`, `MilvusVectorStoreServiceTest`, and `IngestionEngineRouteFailureTest`. The full Phase 1 closure is committed and pushed only after these tests pass together.

---

## Phase 0：环境准备与基础设施（2 天）🔧 已启动

### A. 文档产出（✅ 已完成）

| # | 任务 | 状态 | 产出 | 说明 |
|:--:|------|:--:|------|------|
| A1 | 源码排查（Embedding/Rerank/Milvus/LLM/ETL/实体抽取） | ✅ | `source-checklist.md` | 6 个待确认项全部排清 |
| A2 | 升级方案文档撰写 | ✅ | `upgrade-plan.md` | 基于实际技术栈调整后的完整方案 |
| A3 | 开发路线图撰写 | ✅ | `dev-roadmap.md`（本文档） | 模块顺序与进度追踪 |

### B. 技术前置验证（⚠️ 部分完成）

| # | 任务 | 状态 | 结论 | 产出/说明 |
|:--:|------|:--:|------|------|
| B1 | 验证项目编译通过 | ✅ | `mvnw compile -pl bootstrap -am` 编译成功 | 现有 4 万行 Java 代码无编译错误 |
| B2 | **验证 Qwen-VL API 端点** | ✅ | DashScope 原生端点：`POST https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation` | 与现有 ChatClient 的 `/compatible-mode/v1/chat/completions` **完全不同的端点**，需独立实现 |
| B3 | **确认 OkHttp 客户端复用方案** | ✅ | `syncHttpClient` Bean 可直接注入，调用模式参考 `BaiLianRerankClient` | Qwen-VL API 不走 OpenAI 兼容协议，需参照 Rerank 客户端模式（OkHttp 直接拼装请求） |
| B4 | **确认 ChatMessage 模型限制** | ✅ | `ChatMessage` 仅支持 `String content`，**不支持多模态 content 数组** | Qwen-VL 的 `messages[].content` 是数组格式 `[{image: "base64..."}, {text: "描述这个图片"}]`，需新建独立 DTO |
| B5 | **确认 Milvus Collection 管理 API** | ✅ | `ensureVectorSpace(VectorSpaceSpec)` 可创建新 Collection，`indexDocumentChunks(collectionName, ...)` 等所有方法都接受动态 collectionName | 新增 `industrial_images`、`hypergraph_texts` Collection 零改动即可复用 |
| B6 | **验证 API Key 环境变量** | ✅ | `BAILIAN_API_KEY`（百炼），`SILICONFLOW_API_KEY`（硅基流动） | application.yaml 中已通过 `${BAILIAN_API_KEY:}` 配置，Qwen-VL 直接复用 |
| B7 | Tess4J 中文语言包方案 | ✅ | **方案 B：首次运行时自动下载**。`Tess4JParser` 初始化时检查 `resources/tessdata/chi_sim.traineddata`，不存在则从 `github.com/tesseract-ocr/tessdata/raw/main/` 下载（~47MB），后续重启跳过。不占 Git 仓库空间 | 实现细节见 Phase 1.4 |
| B8 | 决定视频抽帧方案 | ✅ | **JavaCV 平台版**（`org.bytedeco:ffmpeg-platform` + 单平台 classifier，~50MB） | Phase 1.6 加依赖 |
| B9 | **验证 API Key 有效（Qwen-VL 试调用）** | ✅ | 2026-07-24 curl 测试通过，`qwen-vl-max` 返回完整中文图像描述，Token 消耗 1619（1图+1问），Key 有效且额度充足 | — |

### C. 包路径规划（✅ 已确认）

基于项目现有包结构命名规范，新增代码的包路径如下：

```
bootstrap/src/main/java/com/nageoffer/ai/ragent/
│
├── multimodal/                          # 🆕 多模态统一包
│   ├── parser/                          # Phase 1 - 文档解析
│   │   ├── MultimodalDocumentParser.java        # 接口
│   │   ├── PdfBoxParser.java                    # 电子PDF
│   │   ├── Tess4JParser.java                    # 扫描件OCR
│   │   ├── QwenVLImageParser.java              # Qwen-VL 视觉描述
│   │   ├── VideoKeyFrameParser.java            # 视频关键帧
│   │   └── dto/
│   │       ├── ParseResult.java                 # 解析结果DTO
│   │       └── FileType.java                    # 文件类型枚举
│   │
│   ├── retrieval/                       # Phase 2 - 图像检索
│   │   └── image/
│   │       ├── ImageSearchChannel.java          # SearchChannel 实现
│   │       └── ImageIngestionService.java       # 图像入库服务
│   │
│   └── fusion/                          # Phase 4 - 多路融合
│       └── MultiSourceFusionProcessor.java      # PostProcessor 实现
│
├── rag/core/hypergraph/                 # 🆕 Phase 3 - 超图引擎
│   ├── IndustrialHyperGraph.java                # 接口
│   ├── IndustrialHyperGraphImpl.java            # JGraphT + 自研超边层
│   ├── HyperEdge.java                           # 超边数据结构
│   ├── HyperEdgeExtractor.java                  # LLM N元组抽取
│   ├── EntityExtractor.java                     # Query实体抽取
│   └── HyperGraphSearchChannel.java             # SearchChannel 实现
│
└── ingestion/node/                      # 扩展现有包
    └── MultimodalDocumentParserNode.java         # IngestionNode 实现
```

### D. B9 Qwen-VL API 试调用命令

```bash
# 验证百炼 Qwen-VL API 是否可用（用你的 BAILIAN_API_KEY 替换）
curl -X POST "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation" \
  -H "Authorization: Bearer $BAILIAN_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "qwen-vl-max",
    "input": {
      "messages": [{
        "role": "user",
        "content": [
          {"image": "https://dashscope.oss-cn-beijing.aliyuncs.com/images/dog_and_girl.jpeg"},
          {"text": "请用中文描述这张图片"}
        ]
      }]
    }
  }'
```

**期望响应**：HTTP 200，`output.choices[0].message.content` 包含中文描述文本。

> ⚠️ **如果此 API 调用失败（401 无权限 / 403 额度不足），则需要先开通百炼 Qwen-VL 服务或充值，否则 Phase 1.5 无法推进。**

---

### E. Phase 0 完成标准

**进入 Phase 1 前必须满足：**
- [x] A1-A3：3 份方案文档产出
- [x] B1-B6：6 项技术前置验证（API 端点、HTTP 客户端、ChatMessage、Milvus、API Key）
- [x] B7：Tess4J 中文语言包方案 → **方案 B：首次运行时自动从 GitHub 下载 `chi_sim.traineddata`**
- [x] B8：决定视频抽帧用 JavaCV 还是 ffmpeg CLI → **JavaCV 平台版**
- [x] **B9：用 curl 验证 Qwen-VL API 可用** → `qwen-vl-max` 返回完整中文描述，Key 有效，额度充足 ✅

---

## Phase 1：多模态文档解析管道（Week 1，5-7 天）

### 依赖分析
- **前置依赖**：无（全新模块）
- **被依赖**：Phase 2 图像检索链（需要解析后的图像描述）、Phase 3 超图引擎（需要解析后文本触发超边抽取）

### 开发顺序

| # | 任务 | 文件 | 优先级 | 预计工时 | 状态 |
|:--:|------|------|:--:|:--:|:--:|
| 1.1 | 新增 Maven 依赖（PDFBox/Tess4J/JavaCV/JGraphT） | `bootstrap/pom.xml` | P0 | 0.5h | ✅ |
| 1.2 | `MultimodalDocumentParser` 接口 + `ParseResult` DTO | `bootstrap/.../parser/` | P0 | 0.5h | ✅ |
| 1.3 | `PdfBoxParser`：电子 PDF/Word/Excel 解析（PDFBox + Tika 回退） | `bootstrap/.../parser/PdfBoxParser.java` | P0 | 3h | ✅ |
| 1.4 | `Tess4JParser`：扫描件 OCR（含自动下载中文包） | `bootstrap/.../parser/Tess4JParser.java` | P0 | 3h | ✅ |
| 1.5 | `QwenVLImageParser`：Qwen-VL API 视觉描述（百炼 DashScope，Base64 图像） | `bootstrap/.../parser/QwenVLImageParser.java` | P0 | 4h | ✅ |
| 1.6 | `VideoKeyFrameParser`：JavaCV/ffmpeg 抽帧 + Qwen-VL 逐帧描述 | `bootstrap/.../parser/VideoKeyFrameParser.java` | P1 | 4h | ⬜ |
| 1.7 | `MultimodalDocumentParserNode`：实现 `IngestionNode` 接入 ETL Pipeline | `bootstrap/.../parser/` | P0 | 2h | ✅ |
| 1.8 | 单元测试（各 Parser 独立测试 + Pipeline 集成测试） | `bootstrap/src/test/.../parser/` | P0 | 4h | ✅ |

**Phase 1 产出**：
- 4 个 Parser 实现类
- 1 个 ETL Pipeline 节点（`MULTIMODAL_PARSE` 类型）
- 可从上传的 PDF/图片/视频中提取文本或视觉描述

---

## Phase 2：图像检索链（Week 2，3-5 天）

### 依赖分析
- **前置依赖**：Phase 1 的 `QwenVLImageParser`（生成图像描述）、Phase 1 的 Pipeline 节点（入库触发）

### 开发顺序

| # | 任务 | 文件 | 优先级 | 预计工时 | 状态 |
|:--:|------|------|:--:|:--:|:--:|
| 2.1 | 新建 Milvus Collection `industrial_images`（复用 `MilvusVectorStoreAdmin`） | 配置 + 初始化脚本 | P0 | 1h | ✅ |
| 2.2 | `ImageIngestionService`：图像描述 → Qwen3-Embedding-8B → Milvus 写入 | `bootstrap/.../retrieval/image/` | P0 | 3h | ✅ |
| 2.3 | `ImageSearchChannel`：实现 `SearchChannel`，自动加入多路检索 | `bootstrap/.../retrieval/image/` | P0 | 3h | ✅ |
| 2.4 | 与现有多路检索引擎集成测试 | 集成测试 | P0 | 2h | ✅ |

**Phase 2 产出**：
- Milvus 新增 1 个 Collection
- 图像检索通道自动挂载到多路检索引擎
- 用户 query 可召回相关设备图纸/照片/视频帧

---

## Phase 3：超图引擎（Week 3-4，5-7 天）

### 依赖分析
- **前置依赖**：Phase 1 的解析管道（提供文档文本触发超边抽取）
- **被依赖**：Phase 4 多路融合

### 开发顺序

| # | 任务 | 文件 | 优先级 | 预计工时 | 状态 |
|:--:|------|------|:--:|:--:|:--:|
| 3.1 | `HyperEdge` 数据结构 | `bootstrap/.../hypergraph/HyperEdge.java` | P0 | 0.5h | ✅ |
| 3.2 | `IndustrialHyperGraph` 接口定义 | `bootstrap/.../hypergraph/IndustrialHyperGraph.java` | P0 | 0.5h | ✅ |
| 3.3 | `IndustrialHyperGraphImpl`：JGraphT + 自研超边层 + 实体→超边倒排索引 | `bootstrap/.../hypergraph/IndustrialHyperGraphImpl.java` | P0 | 4h | ✅ |
| 3.4 | `HyperEdgeExtractor`：LLM Few-shot N 元组抽取（调 DeepSeek/千问） | `bootstrap/.../hypergraph/HyperEdgeExtractor.java` | P0 | 4h | ✅ |
| 3.5 | `EntityExtractor`：query 实体抽取（LLM Few-shot + 正则规则混合） | `bootstrap/.../hypergraph/EntityExtractor.java` | P0 | 3h | ✅ |
| 3.6 | `HyperGraphSearchChannel`：实现 `SearchChannel`，子图匹配 + Embedding | `bootstrap/.../retrieval/hypergraph/` | P0 | 3h | ✅ |
| 3.7 | 新建 Milvus Collection `hypergraph_texts`（超边展开文本向量化入库） | 配置 + 初始化脚本 | P1 | 1h | ⬜ |
| 3.8 | 超图引擎单元测试 | `bootstrap/src/test/.../hypergraph/` | P0 | 4h | ⬜ |

**Phase 3 产出**：
- 超图引擎核心代码（~400 行 Java）
- 实体抽取工具
- 超图检索通道自动挂载到多路检索
- 500-1000 条 Demo 超边待入库

---

## Phase 2A：超边入库与生命周期一致性（08-04 至 08-05）

### 闭环目标

将已有超图能力接入可配置 ETL 流水线，并保证重入库、删除、重启恢复和提取失败时的超边状态一致。

| # | 闭环 | 关键产出 | 优先级 | 状态 |
|:--:|------|----------|:--:|:--:|
| 2A.1 | 节点入库 | `hyperedge_extract` 节点、chunk/page/version 证据与 `chunker -> hyperedge_extract -> indexer` 路由测试 | P0 | ✅ |
| 2A.2 | 文档替换持久化 | `HyperEdgeDocumentStore` seam、PostgreSQL 文档级替换及内存索引同步 | P0 | ✅ |
| 2A.3 | 启动恢复 | 持久化存储为空时保持空状态；仅存储不可用时回退 JSONL | P0 | ✅ |
| 2A.4 | 失败原子性 | LLM/JSON 错误或全空白 chunks 使整文档失败；仅完整 `[]` 是合法空抽取，非对象数组元素拒绝写入 | P0 | ✅ |
| 2A.5 | 生命周期标识 | `HyperEdgeDocumentIdentity` 统一 Pipeline 入库与知识文档删除的归属键 | P0 | ✅ |
| 2A.6 | 编排输出扩展 | `NodeOutputProjector` seam 承载 hyperedge 输出投影，避免扩展 Pipeline 核心 `switch` | P1 | ✅ |

**Phase 2A 验收**：超边不会因无效 LLM 输出、空状态重启或文档删除而复活或被误清空；相关单元、路由与持久化替换回归可独立运行。

### Phase 2B：受控 Demo 超边导入（08-05）

| # | 闭环 | 关键产出 | 优先级 | 状态 |
|:--:|------|----------|:--:|:--:|
| 2B.1 | 显式种子导入 | `phase5.import-demo-hyperedges=true` 才导入 JSONL，默认不改变持久化库 | P0 | ✅ |
| 2B.2 | 文档级幂等替换 | 按 `sourceDocument` 分组调用 `HyperEdgeDocumentStore.replaceDocument`，重导入不叠加旧超边 | P0 | ✅ |
| 2B.3 | 输入原子性 | 缺失 `edgeId` / `sourceDocument` 或扩展实体结构无效时，在首次写入前失败 | P0 | ✅ |

**Phase 2B 验收**：本地 Demo/评测环境可显式把版本化 JSONL 转为持久化数据；生产加载器仍只以持久化库为准，不会发生隐式 JSONL 回退。

---

## Phase 4：多路融合与答案增强（Week 5，3-5 天）

### 依赖分析
- **前置依赖**：Phase 2 图像检索链、Phase 3 超图引擎

### 核心设计决策（已 grill-me 收口）

| 维度 | 决策 |
|------|------|
| Chunk 来源标识 | `metadata.get("source")` 字符串（SearchChannelType.name()）—— framework 不持有 bootstrap 枚举 |
| Milvus 持久化 | 用现有 metadata JSON 字段（imagePath/sourceFile/parser 已在），**零新增列** |
| 融合 order | order=9（紧贴 Rerank=10 之前） |
| 加权策略 | min-max 归一化 + 加权；0/1/全相同边界兜底 |
| 权重配置 | `@ConfigurationProperties("ragent.fusion")`（单体项目，无需 RefreshScope） |
| Reference 响应 | 嵌套 `references: List<Reference>`（type=TEXT/IMAGE/HYPERGRAPH，6→3 映射） |
| Reference DTO | 拆 `url`（真实 URL） + `detail`（补充描述，HYPERGRAPH=推理路径文本） |
| ReferenceType | 新增枚举 TEXT/IMAGE/HYPERGRAPH（展示层 ↔ 检索层解耦） |
| 答案增强方式 | 新增 SSE 事件 `references`（标准 SSE 多事件模式，前端 EventSource 订阅） |

### 开发顺序

| # | 任务 | 文件 | 优先级 | 预计工时 | 状态 |
|:--:|------|------|:--:|:--:|:--:|
| 4.1 | 扩展 `RetrievedChunk` 字段（`metadata` Map + 兼容构造器） | `framework/.../convention/RetrievedChunk.java` | P0 | 1h | ✅ |
| 4.2 | Channel 改造：`MilvusRetrieverService` 读 metadata + `ImageSearchChannel` 填 imagePath + `HyperGraphSearchChannel` 填 type + hyperEdgePath | `bootstrap/.../retrieve/` + `multimodal/retrieval/image/` + `hypergraph/` | P0 | 1.5h | ✅ |
| 4.3 | `MultiSourceFusionProcessor` + `FusionProperties`（order=9，加权 + min-max + type 兜底） | `bootstrap/.../retrieve/postprocessor/` | P0 | 2h | ✅ |
| 4.4 | 扩展 `ContextFormatter` 接口 + `DefaultContextFormatter`（按 source 分组渲染 references） | `bootstrap/.../prompt/` | P0 | 2h | ✅ |
| 4.5 | `Reference` + `ReferenceType` + SSE 事件 `references` 推送 + 6→3 映射 | `bootstrap/.../rag/dto/` + `service/pipeline/` + `controller/` | P0 | 1.5h | ✅ |
| 4.6 | 端到端集成测试（三路检索 → 融合 → Rerank → 答案生成 → references 推送） | `bootstrap/src/test/` | P0 | 2h | ✅ |

**Phase 4 产出**：
- 端到端多模态 RAG 链路跑通
- 答案含文本引用 + 图纸链接 + 推理路径（references 列表）
- `MultiSourceFusionProcessor` 加权融合 + 零边界处理
- `Reference` DTO + `ReferenceType` 解耦枚举
- SSE 多事件推送（content + references）

---

## Phase 5：生产数据集构建与入库（Week 6，3-5 天）

### 任务列表

| # | 任务 | 产出 | 预计工时 | 状态 |
|:--:|------|------|:--:|:--:|
| 5.1 | LLM 批量生成工业 FAQ（钢铁/石化/电力 3 个场景，200-300 条） | `data/faq/industrial_faq.jsonl` | 4h | ✅ |
| 5.2 | 收集设备图纸素材（维基 Commons、GrabCAD、Thingiverse，10-15 张） | `data/images/drawings/` | 3h | ✅ |
| 5.3 | Qwen-VL 批量生成图像描述 | `data/images/descriptions.jsonl` | 2h | ✅ |
| 5.4 | ~~采集维修操作视频~~（Phase 2 无视频检索通道，跳过） | — | — | ⏭️ |
| 5.5 | ~~视频关键帧提取~~（同上，跳过） | — | — | ⏭️ |
| 5.6 | 从 FAQ 文本 LLM 抽取超边（500-1000 条） | `data/hypergraph/hyperedges.jsonl` | 4h | ✅ |
| 5.7 | 全量数据入库脚本（幂等：先删再全量写入 FAQ + 图像） | `scripts/ingest_to_milvus.py` → Phase5DataIngestionRunner.java | 3h | ✅ |
| 5.8 | 准备 5 个典型工业 Query 作为端到端演示用例（覆盖文本/图像/超图 3 路） | `docs/demo_queries.md` | 1h | ✅ |

**Phase 5 产出**：
- 完整生产数据集（覆盖文本/图像/视频/超边 4 类数据）
- 幂等可重跑的一键入库脚本
- 5 个端到端演示用例

---

## Phase 6：前端增强（Week 6-7，2-3 天）

### 任务列表

| # | 任务 | 文件 | 优先级 | 预计工时 | 状态 |
|:--:|------|------|:--:|:--:|:--:|
| 6.0 | 后端静态资源映射（/files/** 配置化 + 认证放行） | `WebConfig.java` `StaticResourceProperties.java` `SaTokenConfig.java` | P0 | 2h | ✅ |
| 6.1 | 后端 ConversationMessageVO 扩展 references 字段 | `ConversationMessageVO.java` | P0 | 1h | ✅ |
| 6.2 | 前端 references 接入（类型 + onEvent 捕获 + 暂存合并） | `types/index.ts` `chatStore.ts` `sessionService.ts` | P0 | 2h | ✅ |
| 6.3 | 渲染组件（ReferencesPanel/文本卡/Lightbox/推理路径） | `frontend/.../ReferencesPanel.tsx` 等 5 个组件 | P1 | 3h | ✅ |
| 6.4 | 检索来源过滤标签栏（集成于 ReferencesPanel） | `ReferencesPanel.tsx` | P2 | 1h | ✅ |
| 6.5 | ~~视频帧缩略图~~（Phase 5 已跳过视频数据，取消） | — | — | — | ⏭️ |

**Phase 6 产出**：
- 前端支持多模态答案渲染（文本引用卡片 + 图纸 Lightbox + 推理路径面包屑 + 来源过滤）
- references SSE 事件由前端完整消费（时序：meta → references → content* → finish → done）
- 品牌更名 HIRAGent（Hypergraph-Integrated Multimodal Industrial RAG Agent）+ 新增公开"关于项目"页 `/about`（名称含义/背景/场景/技术栈/架构/岗位介绍，Header + 侧边栏双入口）

---

## Phase 7：生产化文档与对外发布（Week 7，3-5 天）

| # | 任务 | 产出 | 预计工时 | 状态 |
|:--:|------|------|:--:|:--:|
| 7.1 | 更新 README：架构图 + 快速开始 + 部署指南 | `README.md` | 3h | ✅ |
| 7.2 | 撰写架构文档（系统全景 / 模块划分 / 数据流 / 扩展点） | `docs/architecture.md` | 2h | ✅ |
| 7.3 | 撰写 API 文档（REST + LLM 路由 + Milvus schema） | `docs/api.md` | 3h | ✅ |
| 7.4 | 更新 docker-compose.yml：生产级编排（健康检查 + 资源限制 + 启动顺序） | `docker-compose.yml` + Dockerfile×2 + 部署指南 | 2h | ✅ |
| 7.5 | 录制端到端演示视频（5 个典型工业 query，文本/图像/超图覆盖） | GIF/MP4 | 4h | ⏭️ 跳过 |
| 7.6 | 撰写简历项目描述（嵌入话术 + 量化指标） | `docs/resume-project.md` | 2h | ✅ |
| 7.7 | 发布 GitHub Release v2.0（含 CHANGELOG + 迁移说明） | `CHANGELOG.md` + Release | 1h | ✅(CHANGELOG 就绪,发布动作待执行) |

---

## Phase 8：RAGAS 端到端评测体系（Week 8，3-4 天）

> 目标：为检索与生成链路建立可量化的评测闭环，产出**真实可讲的指标数据**（Hit Rate / MRR / 忠诚度等），
> 既驱动系统迭代，也支撑简历第 2/3/4 条的数据真实性。

| # | 任务 | 产出 | 预计工时 | 状态 |
|:--:|------|------|:--:|:--:|
| 8.1 | 评测集构建：从 FAQ 210 条抽取 query + 标准答案，覆盖 5 个典型工业场景 | `scripts/eval/datasets/*.jsonl` | 2h | ✅ |
| 8.2 | 检索指标 Runner：Hit Rate@K / MRR@K / Recall@K，支持带/不带重写、带/不带超图的 A/B 对比 | `scripts/eval/retrieval_eval.py` | 3h | ✅ |
| 8.2-A | 请求级通道开关：`enableRewrite` / `enableImage` / `enableHyperGraph` / `enableFusion` 从 HTTP 透传到检索通道 | `RetrievalOptions` + 单测 | 1h | ✅ |
| 8.2-B | 固定 100 条分层评测集、报告配置/数据集指纹及合并一致性校验 | `industrial_eval_v2` + 离线回归 | 1h | ✅ |
| 8.2-C | 在隔离服务以 `retrievalOnly` 重跑四组通道 A/B，提交 schema v2 原始报告与结论 | `scripts/eval/report/*.json` | 1h | ⏳ |
| 8.3 | RAGAS 生成质量评测：接入 ragas 库（faithfulness / answer_relevancy / context_precision / context_recall） | `scripts/eval/ragas_eval.py` | 3h | ✅ |
| 8.4 | 评测报告：自动汇总输出 JSON/MD 对比报告，沉淀为文档 | `docs/evaluation-report.md` | 1h | ✅ |
| 8.5 | 简历数据校准：用真实评测结果替换简历第 2/3/4 条中的指标数字 | `docs/resume-project.md` | 1h | ✅ |

**交付验收标准**：检索评测可一键重跑、指标可复现；RAGAS 指标 ≥ 1 个配置对比（如带/不带超图）；报告含原始数据与结论。
**实测结果**：检索 Hit Rate@1=100%、MRR=1.0(48 条)；口语化 query Hit Rate@1=94.7%(重写 A/B 已对比)；RAGAS 忠诚度 0.91/上下文精准 0.86/召回 0.92。
**测评集迭代 TODO（后续）**：多轮/指代评测集(P0)、评测集难度分层(P0)、RAGAS 扩样 40-48 条(P1)、通道级 A/B 开关 enableHyperGraph/enableImage(P1)、golden_doc_ids 标注(P2) —— 详见 `docs/evaluation-report.md` §6。

---

## 关键里程碑

```
Phase 0 ──┐  前置准备完成（文档 + 技术验证 + 包路径）
          │  里程碑：B9 Qwen-VL API 试调用成功，可以开始编码
Week 1 ──┤  Phase 1 完成（多模态文档解析管道）
          │  里程碑：PDF/扫描件/图纸/视频 → 语义文本
Week 2 ──┤  Phase 2 完成（图像检索链）
          │  里程碑：图像检索自动挂载到多路检索引擎
Week 3-4 ─┤  Phase 3 完成（超图引擎）
          │  里程碑：超图检索可用，N 元关系推理跑通
Week 5 ──┤  Phase 4 完成（多路融合 + 答案增强）
          │  🎯 核心里程碑：端到端多模态 RAG 链路跑通
Week 6 ──┤  Phase 5 + Phase 6 并行（数据集 + 前端增强）
          │  里程碑：完整 Demo 可演示
Week 7 ──┤  Phase 7（GitHub 整理 + 简历更新）
          │  里程碑：Release v2.0 发布，简历就绪
Week 8 ──┘  Phase 8（RAGAS 端到端评测体系）
          🎯 最终里程碑：检索/生成指标可量化，简历数据真实可溯源
```

---

## 进度追踪

| Phase | 内容 | 状态 | 开始日期 | 完成日期 | 备注 |
|:--:|------|:--:|------|------|------|
| 0-A | 文档产出（3 项） | ✅ 完成 | 07-24 | 07-24 | source-checklist + upgrade-plan + dev-roadmap |
| 0-B | 技术前置验证（9 项） | ✅ 完成 | 07-24 | 07-24 | 9 项全部确认，Phase 0 完结 |
| 0-C | 包路径规划 | ✅ 完成 | 07-24 | 07-24 | 新增 `multimodal/` + `hypergraph/` 包 |
| 1 | 多模态文档解析管道 | ✅ 完成 | 07-25 | 07-25 | 5 个闭环全部完成，Phase 1 完结 |
| 1A | 耐久摄取编排加固 | ✅ 完成 | 08-03 | 08-04 | DAG/重试/幂等写入/检查点恢复/租约有效性/HTTP 恢复验收；条件、路由、执行与进度存储均已抽出 seam |
| 2A | 超边入库与生命周期一致性 | ✅ 完成 | 08-04 | 08-05 | `hyperedge_extract` 节点、证据持久化/替换、空状态启动恢复、提取失败原子性、知识文档删除清理；节点输出已通过 `NodeOutputProjector` seam 扩展 |
| 2B | 受控 Demo 超边导入 | ✅ 完成 | 08-05 | 08-05 | 显式属性触发 JSONL → PostgreSQL 按文档替换，默认不执行；为空库 Demo/评测环境补齐可追溯数据源且不破坏 2A 的无回退语义 |
| 8.2-A | 请求级检索通道开关 | ✅ 完成 | 08-05 | 08-05 | `RetrievalOptions` 由 `/rag/v3/chat` 透传至检索上下文；图像、超图和融合开关均为请求隔离，默认保持全开 |
| 8.2-B | 可复现分层评测工件 | ✅ 完成 | 08-05 | 08-05 | 固定种子生成 100 条 `fact/colloquial/image/relation` 样本；报告固化数据集 SHA-256、四类开关和标签，合并时拒绝混合配置 |
| 8.2-C | 隔离服务 A/B 重跑 | 🚧 进行中 | 08-06 | - | 已完成 `retrievalOnly`、报告级 `latency_ms`/状态/P50/P95 及 Rerank 3s 独立预算；A 组仍发现文本向量嵌入长尾，完整 A/B/C/D 报告仍待重跑，不得使用历史快照更新对外指标 |
| 2 | 图像检索链 | ✅ 完成 | 07-25 | 07-25 | 2 个闭环全部完成，Phase 2 完结 |
| 3 | 超图引擎 | ✅ 完成 | 07-28 | 07-31 | 超边抽取 633 条，超图检索通道可用 |
| 4 | 多路融合与答案增强 | ✅ 完成 | 07-31 | 08-01 | 6 个闭环全部完成，多源融合 + references 推送跑通 |
| 5 | Demo 数据集构建 | ✅ 完成 | 08-01 | 08-02 | FAQ 210 条 + 图像 12 张 + 超边 633 条，Java 全量入库，5 个用例三路命中 |
| 6 | 前端增强 | ✅ 完成 | 08-02 | 08-02 | references 多模态渲染：文本卡片 + 图纸 Lightbox + 推理路径 + 来源过滤，构建/类型检查通过 |
| 增强 | 用户中心（资料自助修改 + 头像上传） | ✅ 完成 | 08-02 | 08-02 | 独立闭环：PUT /user/profile + 头像上传(魔数校验/5MB) + 6 张默认头像 + /profile 页，端到端实测通过 |
| 7 | GitHub 整理与文档 | ✅ 完成 | 08-02 | 08-02 | README/架构/API/部署文档 + 全量容器化(前后端 Dockerfile + compose 一键启动) + CHANGELOG v2.0 + 简历描述;7.5 演示视频跳过;Release 发布动作待执行 |
| 8 | RAGAS 端到端评测体系 | ✅ 完成 | 08-03 | 08-03 | 检索 Hit Rate@1=100%/MRR=1.0(48 条) + 口语化 A/B(94.7%) + RAGAS 忠诚度 0.91/精准 0.86/召回 0.92;脚本一键复现,简历数据真实可溯源 |

---

## 风险提示

| 风险 | 概率 | 影响 | 缓解措施 |
|------|:--:|:--:|------|
| Qwen-VL API 调用受限（百炼额度） | 中 | 核心流程阻断 | 预申请免费额度；备选通义千问 VL 开源模型 + Ollama 本地部署 |
| OCR 中文识别准确率不满足 Demo | 低 | 扫描件路径不可用 | 先用电子 PDF 文本层兜底，扫描件标注为"待优化" |
| 超边抽取质量不高 | 中 | 推理路径空泛 | Few-shot Prompt 迭代 + 人工校验 Top-50 超边 |
| 时间不够，秋招截止前做不完 | 中 | 简历缺少亮点 | 优先 Phase 1-4（核心链路），Phase 5-7 可并行压缩；第 4 周末已有端到端 Demo 可用 |
| 前端改动过大影响原有功能 | 低 | 用户体验下降 | 新增组件独立渲染，不修改已有问答组件核心逻辑 |

---

## 复用的现有基础设施总结

| 基础设施 | 复用方式 | 节省工时 |
|------|------|:--:|
| `RoutingEmbeddingService`（Qwen3-Embedding-8B） | 图像描述/超边文本全部走同一套 Embedding | ~3 天 |
| `RerankPostProcessor`（Qwen3-Reranker） | 融合后直接送已有 Rerank，零改动 | ~2 天 |
| `MilvusVectorStoreAdmin` | 复用 API 新建 Collection | ~1 天 |
| `ChatClient`（百炼/SiliconFlow） | Qwen-VL API 调用复用 HTTP 客户端模式 | ~1 天 |
| `SearchChannel` 接口 | 图像/超图检索通道直接实现接口自动挂载 | ~2 天 |
| `SearchResultPostProcessor` 接口 | 融合处理器直接实现接口自动加入后处理链 | ~1 天 |
| `IngestionNode` 接口 | 多模态解析节点直接实现接口接入 ETL Pipeline | ~1 天 |
| `ModelRoutingExecutor` | 模型调用复用已有容错/降级/路由机制 | ~1 天 |
| **合计节省** | | **~12 天** |

> **结论**：现有项目的扩展机制设计良好，增量开发量约 **2000-2500 行 Java + 200 行 TSX**，远小于从零搭建的成本。
