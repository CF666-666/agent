# 工业Ragent智研中枢 —— 多模态 RAG + 超图索引 升级技术方案

> **文档定位**：在现有项目基础上，面向央国企（国家电网、中石油、烟草、中车等）Java 后端/算法岗秋招，进行功能升级的完整技术方案  
> **版本**：v2.0（融合源码排查结论，基于实际技术栈调整）  
> **日期**：2026-07-24

---

## 目录

1. [现有项目技术画像](#1-现有项目技术画像)
2. [升级总览：为什么做、做什么、不做完会怎样](#2-升级总览)
3. [模块一：多模态文档解析管道](#3-模块一多模态文档解析管道)
4. [模块二：图像检索链](#4-模块二图像检索链)
5. [模块三：超图引擎](#5-模块三超图引擎)
6. [模块四：多路融合与前端增强](#6-模块四多路融合与前端增强)
7. [Demo 数据集构建](#7-demo-数据集构建)
8. [央国企面试话术速查](#8-央国企面试话术速查)
9. [简历嵌入话术](#9-简历嵌入话术)

---

## 1. 现有项目技术画像

### 1.1 项目概况

| 维度 | 详情 |
|------|------|
| **项目名称** | Ragent（工业Ragent智研中枢系统） |
| **技术栈** | Java 17 + Spring Boot 3.5.7 + React 18 + TypeScript |
| **模块结构** | framework（通用框架层）→ infra-ai（AI 基础设施层）→ bootstrap（核心业务层） + mcp-server（MCP 独立服务） |
| **代码规模** | 后端 ~40000 行 / 400+ 源文件，前端 ~18000 行，MySQL 20+ 张业务表 |
| **向量数据库** | Milvus 2.6.x（SDK: `milvus-sdk-java:2.6.6`） |
| **LLM 平台** | 百炼（阿里云）、SiliconFlow（硅基流动）、Ollama（本地）、vLLM（扩展中） |
| **Embedding 模型** | **Qwen3-Embedding-8B**，维度 **1536**，OpenAI 兼容协议，支持 SiliconFlow API + Ollama 本地双路 |
| **Rerank 模型** | **Qwen3-Reranker**（SiliconFlow API），已有独立 `RerankPostProcessor` |
| **文档解析** | Apache Tika 3.2（仅文本层） |

### 1.2 现有核心能力（不需要动的部分）

```
┌─────────────────────────────────────────────────────────────────┐
│                已有模块（不修改核心逻辑，仅在接口层扩展）            │
│                                                                 │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌──────────┐ │
│  │ ETL Pipeline │ │ 意图路由     │ │ 查询重写     │ │ 交互澄清  │ │
│  │ IngestionNode │ │ IntentClassifier│ │ QueryRewriter│ │ IntentGuidance│ │
│  │ 6类节点/DFS  │ │ 三层树形     │ │ 上下文补全   │ │ 置信度引导│ │
│  └─────────────┘ └─────────────┘ └─────────────┘ └──────────┘ │
│                                                                 │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────────────────┐   │
│  │ 多路检索     │ │ Rerank 重排  │ │ 模型路由 & 容错           │   │
│  │ 意图定向     │ │ RerankPost-  │ │ 优先级调度/熔断/降级       │   │
│  │ + 全局向量   │ │ Processor   │ │ 首包探测/健康检查         │   │
│  └─────────────┘ └─────────────┘ └─────────────────────────┘   │
│                                                                 │
│  ┌─────────────┐ ┌─────────────────────────────────────────┐   │
│  │ 会话记忆     │ │ ETL 编排引擎                              │   │
│  │ 滑动窗口     │ │ SpEL + JSON 双模条件路由                    │   │
│  │ + 自动摘要   │ │ 6 类节点 / DFS 环检测                       │   │
│  └─────────────┘ └─────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### 1.3 现有扩展机制（新增模块如何接入）

| 扩展点 | 接口 | 说明 |
|--------|------|------|
| **新增检索通道** | `SearchChannel` | 注入 Spring Bean 即可自动被多通道引擎发现 |
| **新增后置处理器** | `SearchResultPostProcessor` | 自动加入后处理链，指定 order 控制顺序 |
| **新增模型供应商** | `ChatClient` / `EmbeddingClient` | 实现接口注册 Bean 即可参与路由 |
| **新增 ETL 节点** | `IngestionNode` | 只需实现 `getNodeType()` + `execute(context, config)` |
| **新增 MCP 工具** | `MCPToolExecutor` | 自动被 `DefaultMCPToolRegistry` 发现 |

---

## 2. 升级总览

### 2.1 为什么要升级

| 维度 | 当前 | 升级后 |
|------|------|--------|
| **模态覆盖** | 纯文本文档（PDF/Word 文本层） | 文本 + 扫描版 PDF + 图纸/照片 + 维修视频 |
| **检索范式** | 平面向量检索（相似度匹配） | 平面检索 + 超图结构检索（N 元关系推理） |
| **答案丰富度** | 纯文本引用 | 文本引用 + 图纸/关键帧 + 推理路径 |
| **技术深度** | 工程化优秀的 RAG 系统 | 工程化 + 前沿学术方向的 RAG 系统 |
| **简历区分度** | Java RAG 项目（已有竞争力） | Java 多模态 RAG + 超图（秋招最强区分度之一） |

### 2.2 核心改进点

| # | 改进点 | 技术方案 | 新增代码量 |
|:--:|------|------|:--:|
| 1 | **多模态支持** | PDFBox + Tess4J + Qwen-VL API 描述（零 GPU） | ~800 行 Java |
| 2 | **图像检索链** | Image-to-Text 描述转换 + 复用现有 Embedding + 新建 Milvus Collection | ~200 行 Java |
| 3 | **超图引擎** | JGraphT + 自研超边层 + LLM 抽取 + 子图匹配 | ~400 行 Java |
| 4 | **多路融合** | 三路加权融合 + 复用现有 Rerank | ~150 行 Java |
| 5 | **前端增强** | 答案中附图/视频帧/推理路径展示 | ~200 行 TSX |

### 2.3 技术选型（基于现有技术栈的增量选择）

| 组件 | 选型 | 理由 |
|------|------|------|
| **Embedding** | **不动**：Qwen3-Embedding-8B（1536 维） | 国产模型，已跑通，图像描述共用 |
| **Rerank** | **不动**：Qwen3-Reranker（已有 `RerankPostProcessor`） | 国产模型，已集成，融合后直接送 |
| **LLM** | **不动**：已有 ChatClient 三路路由 + 百炼/硅基/Ollama | 国产平台全覆盖 |
| **OCR** | **新增**：Tess4J（~50MB 含中文包） | 纯 Java，CPU 执行，零 GPU |
| **PDF** | **升级**：Apache PDFBox 3.0.1（当前用 Tika 仅文本层） | 支持表格/结构提取 |
| **视觉理解** | **新增**：Qwen-VL API（百炼 DashScope） | 国产模型，云端调用，零 GPU |
| **视频抽帧** | **新增**：JavaCV（或 ffmpeg CLI） | 轻量，仅抽关键帧 |
| **图引擎** | **新增**：JGraphT 1.5.2 | Java 生态标准，~3MB |
| **Milvus** | **不动**：2.6.x（新增 2 个 Collection） | 已有 SDK，零学习成本 |

### 2.4 架构总览

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         Spring Boot 3.5.7（不变）                          │
│                                                                          │
│  ┌────────────────────────── 已有模块（不动） ──────────────────────────┐ │
│  │ Pipeline(ETL) │ 意图路由  │ 查询重写 │ 交互澄清 │ 会话记忆 │ 链路追踪│ │
│  │ BM25检索      │ Rerank重排│ 模型路由 │ RAGAS评测│ MCP工具  │        │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                          │
│  ┌────────────────────────── 新增模块（轻量嵌入） ──────────────────────┐ │
│  │                                                                      │ │
│  │  ┌────────────────────────┐  ┌────────────────────────┐             │ │
│  │  │ 多模态文档解析管道       │  │ 图像检索链              │             │ │
│  │  │ ├ PDFBox (电子PDF/表格) │  │ Qwen-VL 描述 → Qwen3-  │             │ │
│  │  │ ├ Tess4J (扫描件OCR)   │  │ Emb-8B Embedding →     │             │ │
│  │  │ ├ QwenVLImageParser   │  │ Milvus industrial_images│             │ │
│  │  │ └ VideoKeyFrameParser │  │ Collection 检索         │             │ │
│  │  └────────────────────────┘  └────────────────────────┘             │ │
│  │                                                                      │ │
│  │  ┌────────────────────────┐  ┌────────────────────────┐             │ │
│  │  │ 超图引擎 (JGraphT)       │  │ 多路融合 & 答案增强     │             │ │
│  │  │ ├ LLM N元组抽取         │  │ 文本+图像+超图 → 加权  │             │ │
│  │  │ ├ 实体→超边倒排索引      │  │ → 已有Rerank 精排      │             │ │
│  │  │ └ 子图匹配+自然语言展开  │  │ → 附图纸+推理路径       │             │ │
│  │  └────────────────────────┘  └────────────────────────┘             │ │
│  └──────────────────────────────────────────────────────────────────────┘ │
│                                                                          │
│                          ┌─── HTTP ───┐                                  │
│                          ↓             ↓                                  │
│  ┌──────────────────────────┐  ┌──────────────────────────┐             │
│  │ 外部 API（已有，扩展）     │  │ 本地 / 云服务（已有）     │             │
│  │ 百炼 API（新增 Qwen-VL）  │  │ Milvus 2.6              │             │
│  │ SiliconFlow Embedding    │  │ Ollama 本地              │             │
│  │ SiliconFlow Rerank       │  │ S3 对象存储（RustFS）    │             │
│  └──────────────────────────┘  └──────────────────────────┘             │
└──────────────────────────────────────────────────────────────────────────┘
```

### 2.5 模块依赖关系

```
多模态文档解析管道 ──→ 超图引擎（提供N元组数据源）
                  ──→ 图像检索链（提供图像语义描述入库）

文本检索链（已有） ──┐
图像检索链（新增） ──┼──→ 多路融合器 ──→ 已有 RerankPostProcessor ──→ 答案生成（增强版）
超图引擎（新增）   ──┘
                          │
                          └──→ 超边展开文本也用 Qwen3-Embedding-8B 向量化
                              → 存入 Milvus hypergraph_texts Collection
                              → 便于与文本/图像结果统一分数融合
```

---

## 3. 模块一：多模态文档解析管道

### 3.1 职责

将非纯文本文档（扫描件、图纸、照片、视频）转换为可供检索的语义文本。

### 3.2 基于现有代码的接入方式

```java
/**
 * 新增 IngestionNode：多模态文档解析节点
 * 实现 IngestionNode 接口，自动被 ETL Pipeline 编排引擎发现
 */
@Component
public class MultimodalDocumentParserNode implements IngestionNode {

    private final MultimodalDocumentParser parser; // 注入解析管道

    @Override
    public String getNodeType() {
        return "MULTIMODAL_PARSE";  // 新节点类型
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        File file = context.getCurrentFile();
        FileType type = detectFileType(file);
        ParseResult result = parser.parse(file, type);

        // 分流入库策略不变，通过 NodeResult 传递到下游节点
        context.setAttribute("parseResult", result);
        context.setAttribute("fileType", type);

        return NodeResult.success();
    }
}
```

### 3.3 四个解析器实现

| 实现类 | 文件类型 | 核心依赖 | 处理逻辑 |
|--------|---------|---------|---------|
| `PdfBoxParser` | 电子 PDF/Word/Excel | PDFBox 3.0.1 + POI 5.2.5 | 提取文本 + 表格 → 结构化文本 |
| `Tess4JParser` | 扫描版 PDF/图片 | Tess4J 5.9.0（中文 `chi_sim`） | 图片 → OCR → 文本 |
| `QwenVLImageParser` | 设备图纸/现场照片 | 百炼 DashScope API（Qwen-VL） | 图像 → Base64 → API → 中文视觉描述 |
| `VideoKeyFrameParser` | 维修操作视频 | JavaCV 1.5.9 或 ffmpeg CLI | 每 5s 抽帧 → Qwen-VL API 描述 → 时间戳索引 |

### 3.4 Image-to-Text 描述转换（核心轻量化策略）

```
图像.jpg → Qwen-VL API 描述 → "这是一张2号轧机主轴承装配图，展示了密封圈、润滑管路..."
        → Qwen3-Embedding-8B（已有）→ [0.12, -0.34, ..., -0.05]（1536维）
        → 存入 Milvus industrial_images Collection

用户查询 "2号轧机轴承爆炸图" → Qwen3-Embedding-8B → Milvus 图像 Collection → 命中
```

**为什么不用 CLIP / BGE-Visualized-M3？**
1. 不需要部署视觉 Embedding 模型（省 3-5GB 磁盘 + GPU）
2. 中文描述与用户中文 query 语义对齐更好（Qwen3-Embedding-8B 擅长中文）
3. 完全复用现有 `RoutingEmbeddingService`，只需新建 Milvus Collection
4. 总增量 < 150MB（Tess4J 中文包 + PDFBox + JGraphT）

### 3.5 入库流程

```
文档上传
  │
  ├─→ 电子 PDF/Word ──→ PDFBox ──→ 文本 → 切 chunk → Qwen3-Emb-8B → Milvus ragent_knowledge（已有）
  │
  ├─→ 扫描件 PDF ──→ Tess4J OCR ──→ 文本 → 同上
  │
  ├─→ 设备图纸/照片 ──→ Qwen-VL API 描述 ──→ 中文描述文本
  │       │                                    ├─→ Qwen3-Emb-8B → Milvus industrial_images（新增）
  │       │                                    └─→ 同时触发 LLM 超边抽取 → 超图引擎入库
  │       └──→ 保留原始图片路径到 S3，答案引用时附图
  │
  └─→ 维修视频 ──→ JavaCV/ffmpeg 抽帧 ──→ 每 5s 一帧 → Qwen-VL API → 同图像路径
```

---

## 4. 模块二：图像检索链

### 4.1 设计亮点：几乎不需要新代码

图像描述文本和用户 query 都用同一个 **Qwen3-Embedding-8B** 做向量化（1536 维），检索逻辑**完全复用**现有 `MilvusRetrieverService`。

### 4.2 实现

```java
/**
 * 图像检索通道 —— 实现 SearchChannel 接口，自动加入多路检索引擎
 */
@Component
public class ImageSearchChannel implements SearchChannel {

    private final EmbeddingService embeddingService;  // 已有：RoutingEmbeddingService
    private final MilvusRetrieverService milvusRetriever; // 已有

    @Override
    public SearchChannelResult execute(SearchContext context) {
        // 和文本检索完全一样：query → Embedding → Milvus 检索
        // 唯一区别：指定 Collection = "industrial_images"
        List<Float> queryVector = embeddingService.embed(context.getMainQuestion());
        return milvusRetriever.search(
            "industrial_images",    // ← 新建的 Collection
            queryVector,
            context.getTopK()
        );
    }
}
```

### 4.3 新建 Milvus Collection

```java
// 在 MilvusVectorStoreAdmin 中新增创建方法（复用现有 API）
milvusAdmin.createCollection(
    "industrial_images",
    "图像语义描述 Collection",
    1536  // 与 Qwen3-Embedding-8B 维度一致
);
```

**Schema**：`id(VarChar/PK)`, `description(VarChar/65535)`, `image_path(VarChar)`, `source_file(VarChar)`, `metadata(JSON)`, `embedding(FloatVector/1536)`

---

## 5. 模块三：超图引擎

### 5.1 为什么工业场景需要超图

| 传统知识图谱（Graph） | 超图（Hypergraph） |
|---------------------|-------------------|
| 每条边只连接 **2 个节点**（二元关系） | 每条超边可连接 **N 个节点**（N 元关系） |
| "轧机A" —[故障]→ "轴承过热" | {"轧机A", "冬季低温(-15℃)", "润滑油粘度超标", "轴承过热停机", "SOP-2024-001"} |

**工业场景典型 N 元事实**：
```
超边: {"1号鼓风机", "夏季高温(40℃)", "冷却水流量不足", "电机过载跳闸", "SOP-2024-001"}
       └─ 设备 ─┘  └─── 工况 ───┘  └─── 直接原因 ───┘  └─── 后果 ───┘  └─ 关联文档 ─┘
```

### 5.2 核心接口

```java
public interface IndustrialHyperGraph {
    // 构建：从文档抽取 N 元组超边
    List<HyperEdge> extractHyperedges(String documentText);

    // 存储：构建实体→超边倒排索引
    void addHyperedges(List<HyperEdge> edges);

    // 检索：根据 query 实体做子图匹配，按命中数降序
    List<HyperEdge> matchSubgraph(Set<String> queryEntities, int maxEdges);

    // 展开：超边 → 自然语言（供 LLM 推理 + Embedding 向量化）
    String expandToText(HyperEdge edge);
}
```

### 5.3 实现架构

```java
@Service
public class IndustrialHyperGraphImpl implements IndustrialHyperGraph {

    private final Graph<String, DefaultEdge> baseGraph;  // JGraphT 辅助图遍历
    private final List<HyperEdge> hyperEdges;             // 超边列表
    private final Map<String, Set<Integer>> entityToEdgeIndex; // 节点→超边ID 倒排索引 ← 核心

    @Override
    public List<HyperEdge> extractHyperedges(String documentText) {
        // 方案：调 DeepSeek/千问 LLM 做 Few-shot N 元组抽取
        // Prompt: "从以下工业文本中抽取 N 元组超边，每条超边包含：设备、工况、参数、故障、关联文档"
        return extractViaLLM(documentText);  // 复用现有 ChatClient
    }

    @Override
    public List<HyperEdge> matchSubgraph(Set<String> queryEntities, int maxEdges) {
        // 1. 倒排索引检索：任意实体命中 → 候选超边
        // 2. 按命中实体数降序排列
        // 3. 返回 Top-K 超边
    }
}
```

### 5.4 实体抽取（需新建）

由于项目无独立 NER 模块，超图子图匹配需要从 query 中抽取实体：

```java
/**
 * 实体抽取工具 —— 超图检索的前提
 */
@Component
public class EntityExtractor {

    private final ChatClient chatClient;  // 复用已有 LLM 客户端

    public Set<String> extractFromQuery(String query) {
        // 方案 A（Demo 推荐）：LLM Few-shot
        // "提取文本中的工业实体（设备型号、故障代码、参数名、工况描述）：\n{query}"
        // 返回: {"2号轧机", "轴承", "冬季低温", "温度过高"}

        // 方案 B（精确匹配）：正则 + 词典
        // Pattern.compile("\\d+号\\w+")  // 匹配 "2号轧机"
        // + 故障代码词典（SOP-XXX, MF-XXX）
    }
}
```

### 5.5 超图检索链接入

```java
/**
 * 超图检索通道 —— 实现 SearchChannel，自动加入多路检索
 */
@Component
public class HyperGraphSearchChannel implements SearchChannel {

    private final IndustrialHyperGraph hyperGraph;
    private final EntityExtractor entityExtractor;
    private final EmbeddingService embeddingService;  // 已有

    @Override
    public SearchChannelResult execute(SearchContext context) {
        // Step 1: 实体抽取
        Set<String> entities = entityExtractor.extractFromQuery(context.getMainQuestion());

        // Step 2: 超图子图匹配
        List<HyperEdge> matched = hyperGraph.matchSubgraph(entities, 10);

        // Step 3: 超边展开为自然语言 → Embedding → 统一分数
        for (HyperEdge edge : matched) {
            String text = hyperGraph.expandToText(edge);
            float[] vector = embeddingService.embedVector(text);
            // → 作为 RetrievalChunk 加入结果，source=HYPERGRAPH
        }
    }
}
```

---

## 6. 模块四：多路融合与前端增强

### 6.1 融合策略

```java
/**
 * 在已有 RerankPostProcessor 之前，新增一个融合处理器
 */
@Component
public class MultiSourceFusionProcessor implements SearchResultPostProcessor {

    // 权重配置（可调参）
    private static final double TEXT_WEIGHT  = 0.50;
    private static final double IMAGE_WEIGHT = 0.25;
    private static final double HYPER_WEIGHT = 0.25;

    @Override
    public int getOrder() {
        return 9;  // 在 Rerank（order=10）之前执行
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                         List<SearchChannelResult> results,
                                         SearchContext context) {
        // 根据 source 标识加权 → 排序 → 送 Rerank
    }
}
```

**后置处理器链（升级后）**：
```
多路检索结果 → 去重（已有） → 多源融合（新增,order=9） → Rerank重排（已有,order=10） → Top-K
```

### 6.2 答案生成增强

```java
// 在已有 Prompt 组装逻辑中增强：附加图像路径 / 超图推理路径
// 修改 RAGPromptService，识别 RetrievedChunk 的 source 标识

if (chunk.getSource() == RetrievalSource.HYPERGRAPH) {
    prompt += "[推理路径] " + chunk.getHyperEdgePath() + "\n";
}
if (chunk.hasImage()) {
    prompt += "[附图] " + chunk.getImageUrl() + "\n";  // S3 预签名 URL
}
```

### 6.3 前端增强（React）

在答案渲染区域新增：
- **图片预览**：点击附图链接弹出 Lightbox
- **推理路径折叠面板**：超图推理路径以面包屑形式展示
- **视频帧预览**：视频关键帧缩略图 + 时间戳跳转

---

## 7. Demo 数据集构建

| 数据类别 | 来源 | 预计规模 |
|---------|------|---------|
| **工业FAQ（文本）** | LLM 生成（DeepSeek/Qwen）钢铁/石化/电力场景 | 200-300 条 QA 对 |
| **设备图纸** | 维基百科 Commons / GrabCAD / Thingiverse | 10-15 张（爆炸图/装配图） |
| **设备照片** | 自拍或公开工业图集 | 5-10 张 |
| **维修视频** | YouTube 设备维修教学视频（标注来源） | 3-5 段 |
| **超边数据** | 从 FAQ 文本中 LLM 抽取 N 元组 | 500-1000 条超边 |

---

## 8. 央国企面试话术速查

| 面试官可能的提问 | 回答要点 |
|----------------|---------|
| "为什么用超图而不是知识图谱？" | 工业场景大量存在 N 元关系（设备-工况-参数-故障），传统 KG 每条边只能连 2 个节点。超边连接 N 个节点，天然表达完整工业事实单元。引用 NeurIPS 2025 HyperGraphRAG。 |
| "多模态怎么做的？" | Image-to-Text 描述转换——图像经 Qwen-VL API 生成中文描述，再用 Qwen3-Embedding-8B 向量化。零 GPU 依赖，完全复用现有 Embedding 基础设施。国产模型全家桶（Qwen3-Embedding + Qwen-VL + Qwen3-Reranker）。 |
| "Java 怎么做 AI？" | 主流程用 Java + Spring Boot。Embedding/Rerank 通过 OpenAI 兼容协议调用 SiliconFlow API 或本地 Ollama。视觉理解调百炼 Qwen-VL API。这个架构兼顾了 Java 的工程化优势和 AI 生态。 |
| "信创/国产化怎么考虑的？" | 百炼（阿里云）+ 硅基流动 纯国产平台。Qwen 系列模型（阿里开源/国产）。Milvus 是 CNCF 毕业项目。Tesseract 开源 OCR。可部署在国产麒麟 OS + 华为鲲鹏 CPU 上。 |
| "这是你自己做的还是团队？" | Ragent 基础版（4 万行 Java + 1.8 万行前端）由我独立完成。多模态和超图两个方向我做了完整技术方案设计并实现核心 Demo。进入团队可直接在现有 Spring Boot 项目上集成。 |

---

## 9. 简历嵌入话术

### 项目概述（简历头部，替代或补充原有描述）

> **工业Ragent智研中枢系统**（Spring Boot + Milvus + 百炼/硅基 API）  
> 面向流程制造行业（钢铁/石化/电力）的企业级 Agentic RAG 系统。独立设计多通道检索引擎（意图定向+全局向量）、模型路由与容错机制（三态熔断+自动降级）、6 类节点的 ETL 编排引擎。在此基础上扩展**多模态文档解析管道**（PDFBox+Tess4J+Qwen-VL）与**超图索引引擎**（JGraphT+N元工业关系），将检索能力从纯文本延伸至图纸/视频/扫描件，并实现从"关键词匹配"到"关联推理"的检索范式升级。

### 核心亮点（推荐 6 个 bullet points）

1. **ETL 编排引擎**：SpEL + JSON 双模条件路由，6 类节点/DFS 环检测，节点配置持久化 + 独立执行日志
2. **多路检索引擎**：意图定向 + 全局向量并行执行，去重 + Rerank 后处理链，可插拔扩展
3. **模型路由与容错**：多供应商优先级调度 + 三态熔断器 + 首包探测 + 自动降级，单模型故障无感知
4. **会话记忆与意图引导**：滑动窗口 + 自动摘要压缩；树形意图分类 + 置信度不足主动澄清
5. **多模态文档解析（Image-to-Text 轻量化方案）**：Qwen-VL API 生成图像中文描述，复用 Qwen3-Embedding-8B 实现跨模态检索，零 GPU 依赖，增量 < 150MB
6. **超图索引引擎（N 元关系推理）**：JGraphT + LLM 抽取超边 + 实体倒排索引，实现"设备-工况-参数-故障"多实体关联推理，对标 NeurIPS 2025 HyperGraphRAG

---

> **文档版本**：v2.0 | 2026-07-24  
> **与 v1.0 的区别**：已根据实际源码排查结论，修正了 Embedding 模型（Qwen3-Embedding-8B）、Rerank 状态（已有独立模块）、Milvus 版本（2.6.x）等关键参数。所有新增模块均基于现有扩展接口设计，最大化复用存量代码。

---

## 10. RAGAS 端到端评测体系（Phase 8）

### 10.1 目标与背景

为检索与生成链路建立**可量化、可复现**的评测闭环，目的有三：
1. **简历数据真实性**：当前简历第 2/3/4 条中的指标（Hit Rate / MRR / 忠诚度等）缺乏评测支撑，面试易被追问穿帮；
2. **系统迭代驱动**：A/B 对比（带/不带查询重写、带/不带超图通道）为优化提供量化依据；
3. **面试亮点**：评测体系本身是 RAG 项目的高分能力项。

### 10.2 评测集构建（8.1）

- 数据源：Phase 5 生产数据集 —— FAQ 210 条（`data/faq/industrial_faq.jsonl`）、5 个典型工业 query；
- 抽取策略：FAQ 每条含标准问题/答案，直接作为 `query + golden_answer` 评测样本；另构造 10~20 条多轮改写样本；
- 产出格式：`scripts/eval/datasets/*.jsonl`（`{query, golden_answer, golden_doc_ids?, scene}`）；
- 场景覆盖：设备故障、装配工艺、图纸参数、产品 FAQ、多模态/图纸问答。

### 10.3 检索指标 Runner（8.2）

- 指标：`Hit Rate@K`（Top-K 是否命中）、`MRR@K`（倒数排名）、`Recall@K`；
- 对照实验：基线（直接检索） vs 查询重写后检索；带超图通道 vs 不带；
- 实现：`scripts/eval/retrieval_eval.py` —— 调用项目检索链路（或直接调 `/rag/v3/chat` 的 references 返回），对评测集批量执行并计算指标；
- 输出：按场景分组的指标表 + 总体汇总。

### 10.4 RAGAS 生成质量评测（8.3）

- 工具：Python `ragas` 库（`pip install ragas`）；
- 指标：`faithfulness`（答案忠诚度）、`answer_relevancy`（回答相关性）、`context_precision` / `context_recall`（上下文精准/召回）；
- 输入：评测集 + 项目实际生成的回答（调用 SSE 接口批量获取，含 references）；
- 实现：`scripts/eval/ragas_eval.py`，配置 LLM 打分模型（复用百炼/硅基 API）；
- A/B 对比：不同配置（是否启用超图/重写）下同一评测集的指标差异。

### 10.5 目录结构

```
scripts/eval/
├── datasets/          # 评测集(可配置生成)
├── retrieval_eval.py  # 检索指标 Runner
├── ragas_eval.py      # RAGAS 生成质量评测
├── llm_client.py      # 项目 API/LLM 客户端封装
└── report/            # 输出报告(JSON/MD)
```

### 10.6 验收标准

- [ ] 检索评测一键重跑，Hit Rate/MRR 指标可复现；
- [ ] RAGAS 指标跑通 ≥ 1 组 A/B 对比（带/不带超图或重写）；
- [ ] 产出 `docs/evaluation-report.md`（含数据表与结论）；
- [ ] 用真实评测结果校准简历第 2/3/4 条指标，数字可溯源。
