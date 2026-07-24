# 源码排查结论 —— 方案文档第 14 节待确认项填空

> **排查日期**：2026-07-24  
> **排查范围**：ragent 项目全部 Java 源码、配置文件、Maven 依赖  
> **目的**：为《多模态RAG + 超图索引技术方案》填写待确认项，消除实施方案中的不确定性

---

## 排查结果总览

| 序号 | 排查项 | 结论 | 对应方案调整 |
|:--:|------|------|------|
| 1 | **Embedding 方案** | ✅ 已确认 | 模型从 BGE-M3 改为 Qwen3-Embedding-8B，维度 1536 |
| 2 | **Rerank 方案** | ✅ 已有独立模块 | 方案中"若暂无独立 Rerank"分支可删除，直接走融合 + 已有 Rerank |
| 3 | **Milvus 版本** | ✅ 2.6.6 | 比方案中假设的版本更新，Collection 操作 API 确认可用 |
| 4 | **LLM 调用入口** | ✅ 统一 ChatClient 接口 | Qwen-VL API 调用可复用现有 HTTP 客户端模式 |
| 5 | **实体抽取模块** | ⚠️ 无独立 NER | 超图子图匹配需新增实体抽取工具，或复用意图分类中的实体识别 |
| 6 | **ETL 节点扩展机制** | ✅ IngestionNode 接口 | 多模态文档解析可无缝嵌入现有 ETL Pipeline |

---

## 详细排查结论

### 1. Embedding 方案

**当前方案：Qwen3-Embedding-8B**

| 维度 | 详情 |
|------|------|
| **模型** | `Qwen/Qwen3-Embedding-8B`（硅基流动）/ `qwen3-embedding:8b-fp16`（Ollama 本地） |
| **维度** | **1536**（配置项 `rag.default.dimension`，在 `application.yaml` 中统一定义） |
| **协议** | OpenAI `/v1/embeddings` 兼容协议 |
| **调用层级** | `EmbeddingService`(业务接口) → `RoutingEmbeddingService`(路由+降级) → `EmbeddingClient`(平台适配) |
| **实现类** | `SiliconFlowEmbeddingClient`（硅基流动 API）、`OllamaEmbeddingClient`（Ollama 本地） |
| **关键文件** | `infra-ai/.../embedding/RoutingEmbeddingService.java`、`AbstractOpenAIStyleEmbeddingClient.java` |

**方案文档需要调整的地方**：

> ~~BGE-M3~~ → **Qwen3-Embedding-8B**，向量维度从 ~~1024~~ → **1536**  
> 所有讨论"复用现有 Embedding 基础设施"的地方，底层模型已是 Qwen3-Embedding-8B，不影响 Image-to-Text 描述转换方案的可行性。  
> **面试话术更新**："采用 Qwen3-Embedding-8B 国产 Embedding 模型，支持 SiliconFlow 云端 + Ollama 本地双路部署，配合 OpenAI 兼容协议的抽象层，实现 Embedding 模型的可插拔替换与自动降级。"

---

### 2. Rerank 方案

**当前方案：已有独立 Rerank 模块！**

| 维度 | 详情 |
|------|------|
| **是否存在** | ✅ **已有**独立 Rerank 后置处理器 |
| **模型** | `Qwen3-Reranker`（硅基流动 API） |
| **架构** | `RerankPostProcessor`（后置处理器链最后一个，order=10） → `RerankService` → `RerankClient` |
| **调用时机** | 多路检索 → 去重 → **Rerank 重排序** → 返回 Top-K |
| **关键文件** | `bootstrap/.../retrieve/postprocessor/RerankPostProcessor.java`、`infra-ai/.../rerank/` |
| **触发条件** | 始终启用（`isEnabled()` 返回 `true`） |

**方案文档需要调整的地方**：

> 原方案中"若已有 BGE-Reranker / 若暂无独立 Rerank"的分支讨论可以删除。  
> **实际方案**：三路检索融合后，取 Top-N 送入已有 Rerank（Qwen3-Reranker）做精排。  
> 融合逻辑在 Rerank **之前**，Rerank 作为后置处理器无需修改，只需确保融合层产出的 `RetrievedChunk` 包含分路来源标记（文本/图像/超图），便于后续答案生成时区分来源。

---

### 3. Milvus 版本与 Collection 操作

| 维度 | 详情 |
|------|------|
| **SDK 版本** | `io.milvus:milvus-sdk-java:2.6.6` |
| **Milvus 服务** | Milvus 2.6.x（docker-compose.yml 定义） |
| **连接方式** | HTTP 协议, `localhost:19530`，10s 连接超时，30s RPC 超时 |
| **当前 Collection** | `ragent_knowledge`（文本向量），Schema: `id(VarChar/PK)`, `content(VarChar/65535)`, `metadata(JSON)`, `embedding(FloatVector/1536)` |
| **索引配置** | HNSW, COSINE 距离, M=48, efConstruction=200, 一致性=BOUNDED |
| **管理入口** | `MilvusVectorStoreService`（写入）、`MilvusVectorStoreAdmin`（Collection 管理）、`MilvusRetrieverService`（检索） |

**新增 Collection 兼容性**：✅ 无问题

> 现有 `MilvusVectorStoreAdmin` 已封装 Collection 创建/检查逻辑，新增 `industrial_images` 和 `hypergraph_texts` 两个 Collection 只需复用同一套 API。向量维度 1536 保持一致。

---

### 4. LLM 调用入口

| 维度 | 详情 |
|------|------|
| **统一接口** | `ChatClient`（`infra-ai/.../chat/ChatClient.java`），支持流式 + 非流式 |
| **已对接平台** | **百炼**（阿里云）、**SiliconFlow**（硅基流动）、**Ollama**（本地）、**vLLM**（后续扩展） |
| **路由机制** | `RoutingChatService` → `ModelSelector` 优先级排序 → `ModelRoutingExecutor` 失败降级 |
| **关键文件** | `infra-ai/.../chat/` 目录下 |
| **HTTP 客户端** | OkHttp 4.12.0 |

**Qwen-VL 接入可行性**：✅ 完全可行

> 现有 `BailianLLMService` 或 `SiliconFlowLLMService` 已封装 HTTP 调用 + API Key 管理，Qwen-VL 多模态调用（图像描述）只需在现有 ChatClient 框架上增加一个接收图像 Base64 的方法，或用 OkHttp 直接调百炼 DashScope API（Qwen-VL 不走 Chat Completion，走 `/multimodal-generation/generation` 端点）。

---

### 5. 实体抽取模块

**当前状态：⚠️ 无独立 NER 模块**

| 维度 | 详情 |
|------|------|
| **是否有独立 NER** | ❌ 没有 |
| **相关能力** | 意图分类器（`IntentClassifier`）在进行意图路由时会识别用户 query 中的主题和领域，但非独立的实体抽取 |
| **意图分类能力** | 树形意图体系：Domain（领域）→ Category（类目）→ Topic（话题），LLM 一次性打分 |

**对方案的影响**：

> 超图引擎的子图匹配需要从 query 中抽取实体（设备型号、故障代码、参数名等）。由于现有项目没有独立 NER 模块，**超图模块需要自带一个轻量实体抽取工具**。  
> **推荐方案**：在 `HyperGraphRetrievalService` 中新增 `EntityExtractor` 组件，用 LLM（调 DeepSeek）做 Few-shot 实体抽取，或基于规则+词典做设备型号/故障代码的精确匹配。此为新开发工作量，不在复用现有代码范围内。

---

### 6. ETL 节点扩展机制

**当前状态：✅ IngestionNode 接口，扩展友好**

| 维度 | 详情 |
|------|------|
| **节点接口** | `IngestionNode`（`bootstrap/.../ingestion/node/IngestionNode.java`） |
| **核心方法** | `String getNodeType()` + `NodeResult execute(IngestionContext context, NodeConfig config)` |
| **上下文** | `IngestionContext` 包含共享状态和数据 |
| **配置** | `NodeConfig` 包含当前节点的配置信息 |
| **现有节点类型** | 6 类（文档抓取 → 解析 → 增强 → 分块 → Embedding → Milvus 写入） |
| **注册方式** | 实现 `IngestionNode` 接口、注册为 Spring Bean 即可 |

**新增方式**：

> 新增 `MultimodalDocumentParserNode` 只需：
> 1. 实现 `IngestionNode` 接口
> 2. `getNodeType()` 返回 `"MULTIMODAL_PARSE"`
> 3. `execute()` 中调用 `MultimodalDocumentParser.parse()`
> 4. 注册为 Spring Bean，自动被 Pipeline 编排引擎发现

---

## 方案文档关键参数修正表

| 原文 | 修正为 |
|------|--------|
| "BGE-M3 文本向量化" | **Qwen3-Embedding-8B**，维度 **1536** |
| "若已有 BGE-Reranker / 若暂无独立 Rerank" | **已有 Rerank（Qwen3-Reranker）**，直接走融合 + 已有 Rerank |
| "向量维度 1024（BGE-M3 典型值）" | **1536** |
| "需排查源码确认" | **已全部确认**（见本文档） |
| "BGE-Reranker Python 微服务" | **已存在**（RerankClient + SiliconFlow/Ollama），无需新建 |

---

## 总结

6 个待确认项已全部排查完毕。**好消息**是：现有项目的 Embedding/Rerank/Milvus/LLM/ETL 基础设施比方案文档预期的更完善，多模态和超图的升级可最大程度**复用现有代码**，增量开发量比原估计更小。

**唯一的新增独立开发模块**是实体抽取（Entity Extractor），因为现有意图路由中的实体识别不够精细，超图子图匹配需要更细粒度的设备型号/故障代码提取能力。
