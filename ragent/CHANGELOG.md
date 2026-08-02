# Changelog

本项目的所有重要变更都将记录在此文件中。格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/),版本遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added
- 一键容器化部署:新增后端/前端 Dockerfile 与 docker compose 编排,冷开机 `docker compose up -d --build` 拉起整套系统
- 健康检查端点 `GET /health`(容器编排探针,免鉴权)

### Fixed
- 检索为空或结果相关度过弱时,不再固定返回"未检索到与问题相关的文档内容",改由 LLM 正常回答(问候/身份/闲聊场景可正常自我介绍,如"我是工业 Ragent...")
- 未检索到有效内容时不发送 references(检索来源/推理路径不再展示);新增弱检索判定(最高相关度 < 0.4 视为未检索到,MCP 场景豁免)
- 系统闲聊 Prompt 重写为工业 Ragent 身份;扩充"关于助手"意图示例问法

## [2.0.0] - 2026-08-02

### Added
- **多模态 RAG 升级**:文本 + 图表 + 图像的联合解析(PDFBox / Tesseract OCR / Qwen-VL 图像语义)
- **图像检索通道**:设备图纸按视觉语义向量化,独立 Milvus `industrial_images` 集合检索
- **超图推理引擎**:工业 N 元超边模型(设备/工况/参数/故障/SOP),倒排索引子图匹配,多跳关系推理
- **多路融合检索**:意图定向 / 向量全局 / 图像语义 / 超图四通道并行,去重 → 加权融合 → Rerank 后处理链
- **references 多模态渲染**:回答附带文本引用卡 / 图纸 Lightbox / 推理路径拓扑,SSE 事件结构化输出
- **用户中心**:个人资料自助修改(用户名/头像)、头像上传(魔数校验 / 5MB / 本地落盘)、6 张内置默认头像
- **静态资源映射**:`/files/**` → 本地 `data/images/`,配置化承载图纸引用与头像
- **品牌更名 HIRAGent**:更新项目标识与文档;新增公开"关于项目"页
- 生产数据集:FAQ 210 条、设备图纸 12 张、超图超边 633 条

### Changed
- 前端引用展示重构:统一来源过滤标签栏(全部 / 文本 / 图像 / 推理路径)
- 登录/会话信息返回真实头像(不再注入第三方默认头像,由前端按用户稳定分配默认头像)
- 用户菜单精简:移除官方文档/哔哩哔哩外链入口

### Fixed
- 全局异常处理新增 404 明确提示(接口不存在时不再误报"系统执行错误")
- 默认管理员(admin)可通过个人资料页修改头像(用户名仍禁止修改,管理端保护不变)

## [1.0.0] - 2026-01

### Added
- 企业级 RAG 智能体平台初始版本
- 多路检索引擎(意图定向 + 全局向量)、树形意图识别、问题重写与拆分
- 会话记忆管理(滑动窗口 + 自动摘要)、模型路由与容错(首包探测 + 自动降级)
- 文档入库 Pipeline 编排、MCP 工具集成、全链路 Trace
- React 管理后台(知识库 / 意图树 / 入库监控 / 链路追踪 / 系统设置)

[Unreleased]: https://github.com/nageoffer/ragent/compare/v2.0.0...HEAD
[2.0.0]: https://github.com/nageoffer/ragent/releases/tag/v2.0.0
[1.0.0]: https://github.com/nageoffer/ragent/releases/tag/v1.0.0
