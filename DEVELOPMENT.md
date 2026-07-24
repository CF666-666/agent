# 开发规范与工作流（所有 AI Agent 必须遵守）

> **适用范围**：Claude Code / CodeBuddy / Cursor / GitHub Copilot / Aider / Codex 等所有 AI 编程助手  
> **触发条件**：任何涉及 Ragent 项目 Java 代码编写、修改、重构的请求  
> **目标项目**：Ragent —— 工业多模态 RAG + 超图索引升级（央国企秋招）

---

## 项目上下文

| 要点 | 说明 |
|------|------|
| **项目** | Ragent（工业Ragent智研中枢系统），多模态 RAG + 超图索引升级 |
| **技术栈** | Java 17 + Spring Boot 3.5.7 + Milvus 2.6 + React 18 |
| **LLM 平台** | 百炼(阿里云) / SiliconFlow / Ollama |
| **Embedding** | Qwen3-Embedding-8B，维度 1536 |
| **Rerank** | Qwen3-Reranker（已有独立模块） |
| **源码位置** | `ragent/bootstrap/src/main/java/com/nageoffer/ai/ragent/` |
| **配置文件** | `ragent/bootstrap/src/main/resources/application.yaml` |
| **POM 文件** | `ragent/bootstrap/pom.xml` |
| **路线图** | `ragent/docs/upgrade/dev-roadmap.md` — 全部任务和进度 |
| **技术方案** | `ragent/docs/upgrade/upgrade-plan.md` — 架构设计 |
| **启动指南** | `docs/quick-start.md` — 克隆→运行 |

---

## 核心工作流（5 步，强制遵守）

### Step 1：方案简述
- 用 3-5 句说明要做什么、产出什么
- 列出新增/修改的文件清单（完整相对路径）
- 列出关键接口签名或类结构
- **等待用户确认后，再进入 Step 2**

### Step 2：编码
- 按文件清单逐个创建/修改
- 代码规范：
  - 包路径 `com.nageoffer.ai.ragent.*`
  - 使用 Lombok（`@Data`, `@Slf4j`, `@RequiredArgsConstructor`）
  - `@Service` / `@Component` / `@RestController` 注解
  - 注入接口而非具体实现
- 新增 Maven 依赖加到 `ragent/bootstrap/pom.xml`

### Step 3：自测
- **必须先跑 `cd ragent && ./mvnw compile -pl bootstrap -am -DskipTests`**
- 对有外部依赖的组件，写测试类验证核心逻辑能跑通
- 测试不通过 → 修复 → 重新自测 → 直到通过 → 才能进 Step 4
- **禁止跳过自测**

### Step 4：展示结果
- 展示：编译结果（BUILD SUCCESS）+ 测试输出 + 文件清单
- **等待用户说"通过"或给出修改意见**

### Step 5：收尾
- 更新 `ragent/docs/upgrade/dev-roadmap.md` 对应任务为 ✅
- `git add . && git commit -m "feat(phaseX): 闭环Y - 做了什么" && git push origin main`
- 然后才能进入下一个闭环

---

## 闭环拆分（按 roadmap 依赖关系）

Phase 1 拆为 5 个闭环（Phase 2~7 同理）：

```
闭环 1：基础设施       → pom.xml 加依赖 + 接口定义
闭环 2：PdfBoxParser   → 电子文档文本提取
闭环 3：Tess4JParser   → 扫描件 OCR（含自动下载中文包）
闭环 4：QwenVL + Video → 图像描述 + 视频关键帧
闭环 5：ETL集成 + 测试 → 接入 Pipeline + 单元测试
```

## 禁止行为

- ❌ 不要一次写多个闭环的代码
- ❌ 不要跳过自测直接给用户看
- ❌ 不要修改已有核心逻辑（Pipeline/检索/路由/Rerank）
- ❌ 不要在用户 review 通过前开始下一闭环
- ❌ 不要在 commit message 中使用模糊描述
- ❌ 不要引入新的 Maven 依赖而未在 Step 1 中说明

## 扩展点（如何接入新功能而不改动已有代码）

| 扩展点 | 接口 | 接入方式 |
|--------|------|---------|
| 新增检索通道 | `SearchChannel` | 实现接口 + 注册 Spring Bean，自动挂载 |
| 新增后处理器 | `SearchResultPostProcessor` | 实现接口，指定 order，自动加入处理链 |
| 新增 ETL 节点 | `IngestionNode` | 实现 `getNodeType()` + `execute()` |
| 新增模型供应商 | `ChatClient` / `EmbeddingClient` | 实现接口，注册 Bean，参与路由 |
| 新增 Milvus Collection | `MilvusVectorStoreAdmin` | 调用 `ensureVectorSpace(VectorSpaceSpec)` |

## 技术约束（Phase 0 已验证）

- **Embedding**：复用 `RoutingEmbeddingService`（Qwen3-Embedding-8B，1536维）
- **Rerank**：复用 `RerankPostProcessor`（Qwen3-Reranker）
- **Qwen-VL API**：独立实现，不走 ChatClient。端点 `POST dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation`，Key 来自 `BAILIAN_API_KEY`
- **Tess4J 中文包**：首次运行时自动从 GitHub 下载 `chi_sim.traineddata`
- **视频抽帧**：JavaCV 平台版，~50MB
- **包路径**：多模态 → `multimodal/`，超图 → `rag/core/hypergraph/`
