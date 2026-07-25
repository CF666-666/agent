# 开发模块顺序与进度追踪

> **目标岗位**：央国企（国家电网、中石油、烟草、中车等）Java 后端/算法岗 秋招  
> **起始日期**：2026-07-24  
> **预计总周期**：7 周（49 天），含 Demo 数据集构建 + GitHub 整理

---

## 开发原则

1. **最小可验证单元**：每个模块完成即有独立可跑的 Demo，不依赖全链路完成
2. **存量不动**：已有 Pipeline/检索/路由/Rerank 核心逻辑零修改，仅通过接口扩展接入
3. **先跑通核心流**、再补边缘情况、最后打磨前端展示
4. **每完成一个 Phase 推一次 GitHub**，维护 commit 历史

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
| 3.6 | `HyperGraphSearchChannel`：实现 `SearchChannel`，子图匹配 + Embedding | `bootstrap/.../retrieval/hypergraph/` | P0 | 3h | ⬜ |
| 3.7 | 新建 Milvus Collection `hypergraph_texts`（超边展开文本向量化入库） | 配置 + 初始化脚本 | P1 | 1h | ⬜ |
| 3.8 | 超图引擎单元测试 | `bootstrap/src/test/.../hypergraph/` | P0 | 4h | ⬜ |

**Phase 3 产出**：
- 超图引擎核心代码（~400 行 Java）
- 实体抽取工具
- 超图检索通道自动挂载到多路检索
- 500-1000 条 Demo 超边待入库

---

## Phase 4：多路融合与答案增强（Week 5，3-5 天）

### 依赖分析
- **前置依赖**：Phase 2 图像检索链、Phase 3 超图引擎

### 开发顺序

| # | 任务 | 文件 | 优先级 | 预计工时 | 状态 |
|:--:|------|------|:--:|:--:|:--:|
| 4.1 | `MultiSourceFusionProcessor`：实现 `SearchResultPostProcessor`（order=9，在 Rerank 之前），三路加权融合 | `bootstrap/.../retrieve/postprocessor/` | P0 | 2h | ⬜ |
| 4.2 | 扩展 `RetrievedChunk` 增加 `source` 标识 + `imagePath` + `hyperEdgePath` 字段 | `framework/.../convention/RetrievedChunk.java` | P0 | 1h | ⬜ |
| 4.3 | 修改 `RAGPromptService`：识别来源类型，附图像路径/推理路径到 Prompt | `bootstrap/.../rag/service/pipeline/` | P0 | 3h | ⬜ |
| 4.4 | 答案生成增强：追加附图链接 + 超图推理路径到输出 | 同上 | P0 | 2h | ⬜ |
| 4.5 | 端到端集成测试（工业 query → 三路检索 → 融合 → Rerank → 答案生成） | 集成测试 | P0 | 4h | ⬜ |

**Phase 4 产出**：
- 端到端多模态 RAG 链路跑通
- 答案含文本引用 + 图纸链接 + 推理路径

---

## Phase 5：Demo 数据集构建（Week 6，3-5 天）

### 任务列表

| # | 任务 | 产出 | 预计工时 | 状态 |
|:--:|------|------|:--:|:--:|
| 5.1 | LLM 批量生成工业 FAQ（钢铁/石化/电力 3 个场景，200-300 条） | `data/faq/industrial_faq.jsonl` | 4h | ⬜ |
| 5.2 | 收集设备图纸素材（维基 Commons、GrabCAD、Thingiverse，10-15 张） | `data/images/drawings/` | 3h | ⬜ |
| 5.3 | Qwen-VL 批量生成图像描述 | `data/images/descriptions.jsonl` | 2h | ⬜ |
| 5.4 | 采集维修操作视频（YouTube，3-5 段，标注来源） | `data/videos/` | 3h | ⬜ |
| 5.5 | 视频关键帧提取 + Qwen-VL 帧描述 | `data/videos/keyframes/` | 3h | ⬜ |
| 5.6 | 从 FAQ 文本 LLM 抽取超边（500-1000 条） | `data/hypergraph/hyperedges.jsonl` | 4h | ⬜ |
| 5.7 | 全量数据入库脚本（Python，调入库 API） | `scripts/ingest_to_milvus.py` | 3h | ⬜ |
| 5.8 | 准备 5 个典型工业 Query 作为 Demo 演示用例 | `demo/demo_queries.md` | 1h | ⬜ |

**Phase 5 产出**：
- 完整 Demo 数据集（覆盖文本/图像/视频/超边 4 类数据）
- 一键入库脚本
- 5 个端到端演示用例

---

## Phase 6：前端增强（Week 6-7，2-3 天）

### 任务列表

| # | 任务 | 文件 | 优先级 | 预计工时 | 状态 |
|:--:|------|------|:--:|:--:|:--:|
| 6.1 | 答案渲染区新增图片预览（Lightbox） | `frontend/.../AnswerDisplay.tsx` | P1 | 3h | ⬜ |
| 6.2 | 超图推理路径面包屑组件 | `frontend/.../HyperGraphPath.tsx` | P1 | 3h | ⬜ |
| 6.3 | 视频帧缩略图 + 时间戳跳转 | `frontend/.../VideoFramePreview.tsx` | P1 | 2h | ⬜ |
| 6.4 | 检索来源过滤面板（文本/图像/超图 可选） | `frontend/.../SourceFilter.tsx` | P2 | 3h | ⬜ |

**Phase 6 产出**：
- 前端支持多模态答案渲染

---

## Phase 7：GitHub 仓库整理与文档（Week 7，3-5 天）

| # | 任务 | 产出 | 预计工时 | 状态 |
|:--:|------|------|:--:|:--:|
| 7.1 | 更新 README：新增多模态架构图 + 快速开始 | `README.md` | 3h | ⬜ |
| 7.2 | 撰写架构文档（本文档 `upgrade-plan.md` 的精简版） | `docs/architecture.md` | 2h | ⬜ |
| 7.3 | 撰写 API 文档 | `docs/api.md` | 3h | ⬜ |
| 7.4 | 更新 docker-compose.yml（保证一键启动） | `docker-compose.yml` | 2h | ⬜ |
| 7.5 | 录制 Demo 视频（5 个典型 query 演示例） | GIF/MP4 | 4h | ⬜ |
| 7.6 | 更新简历项目描述（嵌入话术） | 简历 Word/PDF | 2h | ⬜ |
| 7.7 | 发布 GitHub Release v2.0 | GitHub | 1h | ⬜ |

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
Week 7 ──┘  Phase 7（GitHub 整理 + 简历更新）
          🎯 最终里程碑：GitHub Release v2.0 发布，简历就绪
```

---

## 进度追踪

| Phase | 内容 | 状态 | 开始日期 | 完成日期 | 备注 |
|:--:|------|:--:|------|------|------|
| 0-A | 文档产出（3 项） | ✅ 完成 | 07-24 | 07-24 | source-checklist + upgrade-plan + dev-roadmap |
| 0-B | 技术前置验证（9 项） | ✅ 完成 | 07-24 | 07-24 | 9 项全部确认，Phase 0 完结 |
| 0-C | 包路径规划 | ✅ 完成 | 07-24 | 07-24 | 新增 `multimodal/` + `hypergraph/` 包 |
| 1 | 多模态文档解析管道 | ✅ 完成 | 07-25 | 07-25 | 5 个闭环全部完成，Phase 1 完结 |
| 2 | 图像检索链 | ✅ 完成 | 07-25 | 07-25 | 2 个闭环全部完成，Phase 2 完结 |
| 3 | 超图引擎 | ⬜ 待开始 | | | Week 3-4 |
| 4 | 多路融合与答案增强 | ⬜ 待开始 | | | Week 5 |
| 5 | Demo 数据集构建 | ⬜ 待开始 | | | Week 6 |
| 6 | 前端增强 | ⬜ 待开始 | | | Week 6-7 |
| 7 | GitHub 整理与文档 | ⬜ 待开始 | | | Week 7 |

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
