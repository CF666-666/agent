# Ragent 文档导航

> 工业级 Agentic RAG 智能体平台(多模态 RAG + 超图推理)。本文档为**项目文档总索引**,按用途分类,每篇一句话说明。

## 快速上手

| 想做什么 | 看这篇 |
|---------|--------|
| 了解项目是什么、有哪些能力 | [项目 README](../README.md) |
| 快速启动(一键容器化) | [部署指南](deployment.md) |
| 查看全部接口 | [API 文档](api.md) |
| 演示 5 个典型工业问题 | [演示 Query 集](demo_queries.md) |

## 文档目录

```
docs/  (本文档所在)
├── README.md               # 文档导航(本页)
├── architecture.md         # 架构文档
├── api.md                  # API 文档
├── deployment.md           # 部署指南(一键容器化)
├── evaluation-report.md    # RAGAS 端到端评测报告
├── resume-project.md       # 简历项目描述
├── demo_queries.md         # 演示 Query 集
├── multi-channel-retrieval.md  # 多通道检索架构
├── quick-start.md          # 详细快速开始
├── refactoring-summary.md  # 多通道检索重构总结
├── examples/
│   └── pdf-ingestion-example.md  # PDF 入库完整示例
└── upgrade/                # 升级开发文档(多模态 RAG 升级)
    ├── dev-roadmap.md      # 开发路线图(Phase 0-8 进度)
    ├── upgrade-plan.md     # 升级技术方案
    └── source-checklist.md # 源码排查清单
```

## 按分类索引

### 项目入口
| 文档 | 功能 |
|------|------|
| [README](../README.md) | 项目主文档:核心能力、技术架构、快速开始 |
| [CHANGELOG](../CHANGELOG.md) | 版本变更日志(v1.0 / v2.0 / Unreleased) |

### 技术设计
| 文档 | 功能 |
|------|------|
| [architecture.md](architecture.md) | 系统全景图、模块划分、数据流、扩展点、数据模型 |
| [api.md](api.md) | 17 个 Controller 接口、SSE 协议、LLM 路由、Milvus Schema |
| [multi-channel-retrieval.md](multi-channel-retrieval.md) | 多通道检索引擎设计(向量/意图/关键词/图像/超图 + 后处理链) |
| [refactoring-summary.md](refactoring-summary.md) | 多通道检索架构重构的目标与内容 |

### 部署运维
| 文档 | 功能 |
|------|------|
| [deployment.md](deployment.md) | 一键容器化部署、数据持久化、端口拓扑、故障排查 |
| [quick-start.md](quick-start.md) | 手动/容器两种启动方式、环境变量、演示数据说明 |
| `resources/docker/lightweight/README.md` | 低配环境轻量部署(内存限制) |

### 评测与面试
| 文档 | 功能 |
|------|------|
| [evaluation-report.md](evaluation-report.md) | RAGAS 评测:检索 Hit Rate@1=100%/MRR=1.0、忠诚度 0.91、测评集改进方向 |
| [resume-project.md](resume-project.md) | 简历 4 条工作内容、量化指标、面试应答 |
| [demo_queries.md](demo_queries.md) | 5 个典型工业演示问题(故障/工艺/图纸) |

### 升级开发流程
| 文档 | 功能 |
|------|------|
| [upgrade/dev-roadmap.md](upgrade/dev-roadmap.md) | 开发路线图:Phase 0-8 任务、里程碑、进度追踪 |
| [upgrade/upgrade-plan.md](upgrade/upgrade-plan.md) | 多模态 RAG + 超图索引完整技术方案 |
| [upgrade/source-checklist.md](upgrade/source-checklist.md) | 源码排查结论(方案待确认项) |
| `DEVELOPMENT.md`(仓库根) | AI 开发协作规范与 7 步工作流 |

### 示例
| 文档 | 功能 |
|------|------|
| [examples/pdf-ingestion-example.md](examples/pdf-ingestion-example.md) | PDF 入库 Pipeline 完整示例 |

## 推荐阅读顺序

**面试/学习**:README → architecture → api → multi-channel-retrieval → evaluation-report → demo_queries

**部署/演示**:deployment → quick-start → demo_queries

**开发/升级**:upgrade/dev-roadmap → upgrade/upgrade-plan → upgrade/source-checklist
