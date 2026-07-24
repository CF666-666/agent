---
name: ragent-dev-workflow
description: >
  Ragent 项目升级开发的强制工作流。当用户提到"开始编码"、"进入 Phase X"、"开发第X个闭环"、
  "实现XX模块"、"按 roadmap 开发"、或任何涉及 ragent 项目 Java 代码编写/修改的请求时，
  必须先读取仓库根目录的 DEVELOPMENT.md 了解完整项目背景，然后按照 5 步工作流（方案→编码→
  自测→review→收尾）进行最小化闭环开发。每个闭环完成必须更新 dev-roadmap.md。
---

# Ragent 项目开发工作流

> **完整项目上下文和技术约束请先阅读仓库根目录的 `DEVELOPMENT.md`。**

你在参与 Ragent 项目的多模态 RAG + 超图索引升级开发。
目标岗位是央国企 Java 后端/算法岗秋招。开发路线图在 `ragent/docs/upgrade/dev-roadmap.md`。

## 核心原则

1. **先设计，再动手**。写代码前，先用简洁的话讨论设计方案（接口签名、类关系、数据流向），
   用户确认后再编码。
2. **最小化闭环**。每个闭环要能独立编译、独立测试、独立验证，不依赖后面没写的代码。
3. **自测再 review**。每完成一个闭环，必须先自己做测试（编译通过 + 功能验证），测试通过后
   展示结果给用户 review。用户说"通过"或"OK"之后，才能进入下一个闭环。
4. **同步 roadmap**。每个闭环完成后立即更新 `ragent/docs/upgrade/dev-roadmap.md` 中对应
   任务的状态为 ✅，并更新最底部的进度追踪表。

## 闭环的定义

一个闭环 = 一组有向依赖的子任务，完成之后产出可独立验证的结果。
以 Phase 1（多模态文档解析管道）为例，拆为 5 个闭环：

```
闭环 1：基础设施（子任务 1.1 → 1.2）
  1.1 在 ragent/bootstrap/pom.xml 加 Maven 依赖 → 编译通过
  1.2 定义 MultimodalDocumentParser 接口 + ParseResult/FileType DTO
  ✅ review → 进闭环 2

闭环 2：文本类解析器（子任务 1.3）
  1.3 PdfBoxParser — 电子 PDF/Word/Excel 文本提取
  ✅ review → 进闭环 3

闭环 3：OCR 解析器（子任务 1.4）
  1.4 Tess4JParser — 扫描件 OCR，首次运行自动下载中文包
  ✅ review → 进闭环 4

闭环 4：视觉/视频解析器（子任务 1.5 + 1.6）
  1.5 QwenVLImageParser — 百炼 Qwen-VL API 生成图像中文描述
  1.6 VideoKeyFrameParser — JavaCV 抽帧 + 调用 QwenVLImageParser
  ✅ review → 进闭环 5

闭环 5：管道集成（子任务 1.7 + 1.8）
  1.7 MultimodalDocumentParserNode — 实现 IngestionNode 接入 ETL Pipeline
  1.8 补充全部单元测试 → 跑通全量
  ✅ review → Phase 1 完成
```

后续 Phase 2~7 同理，按 dev-roadmap 中的依赖关系拆成闭环。

## 每个闭环的执行步骤（严格按此顺序）

### Step 1：方案简述

- 用简洁的话说明这个闭环要做什么、产出什么
- 列出新增/修改的文件清单（完整路径）
- 列出关键接口签名或类结构
- **等待用户确认**

### Step 2：编码

- 按文件清单逐个创建/修改
- 遵循项目现有代码规范：
  - 包路径格式 `com.nageoffer.ai.ragent.*`
  - 使用 Lombok（`@Data`, `@Slf4j`, `@RequiredArgsConstructor`）
  - `@Service` / `@Component` Spring 注解
  - 注入接口而非具体实现
- 新增的 Maven 依赖加到 `ragent/bootstrap/pom.xml`

### Step 3：自测

- **必须先执行 `cd ragent && ./mvnw compile -pl bootstrap -am -DskipTests` 确认编译通过**
- 对有外部依赖的组件（Qwen-VL API、Tess4J OCR），写一个 main 方法或
  简单测试类在 `bootstrap/src/test/` 下验证核心逻辑能跑通
- 如果测试失败，修复后重新自测，直到通过
- **不要跳过自测**——这是防止 bug 堆积的关键步骤

### Step 4：展示结果

- 展示编译结果（必须出现 BUILD SUCCESS）
- 展示测试输出（控制台日志、截图描述文本等）
- 展示修改的文件清单和关键代码片段
- **等待用户说"通过"或给出修改意见**

### Step 5：收尾

- 用户确认通过后，更新 `ragent/docs/upgrade/dev-roadmap.md`：
  - 当前闭环的每个子任务状态改为 ✅
  - 更新底部进度追踪表中对应 Phase 的状态
- `git add . && git commit -m "feat(phaseX): 闭环X - 做了什么" && git push origin main`
- 然后才能进入下一个闭环的 Step 1

## 禁止行为

- ❌ 不要一次写多个闭环的代码（堆积到后面 review 发现方向错了全白写）
- ❌ 不要跳过自测直接给用户看（你没测过的代码几乎肯定有低级错误）
- ❌ 不要修改已有核心逻辑（Pipeline/检索/路由/Rerank），只通过接口 `SearchChannel`、
  `SearchResultPostProcessor`、`IngestionNode` 等扩展点接入
- ❌ 不要在用户 review 通过前就开始下一闭环
- ❌ 不要在 commit message 里用模糊描述（必须写明 Phase 编号、闭环编号、做了什么）

## 技术约束（Phase 0 已验证确认）

- **Embedding**：复用 `RoutingEmbeddingService`（Qwen3-Embedding-8B，1536 维）
- **Rerank**：复用已有 `RerankPostProcessor`（Qwen3-Reranker，后处理链最后执行）
- **Qwen-VL API**：独立实现，不走 ChatClient 继承体系。调用
  `POST https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation`
  API Key 来自环境变量 `BAILIAN_API_KEY`，OkHttp 客户端复用已有 `syncHttpClient` Bean
- **Milvus**：所有新增 Collection 通过 `MilvusVectorStoreAdmin.ensureVectorSpace()` 创建
- **检索通道**：实现 `SearchChannel` 接口注册为 Spring Bean 即可自动挂载
- **ETL 节点**：实现 `IngestionNode` 接口（`getNodeType()` + `execute()`）即可接入 Pipeline
- **Tess4J 中文包**：`chi_sim.traineddata` 首次运行时自动从 GitHub tesseract-ocr 仓库下载，
  不占 Git 空间
- **视频抽帧**：JavaCV 平台版（`org.bytedeco:ffmpeg-platform` + 单平台 classifier），~50MB
