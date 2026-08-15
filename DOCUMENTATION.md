# Agent 项目文档导航

> 工业级 Agentic RAG 智能体平台（多模态 RAG + 超图推理）。本文档为 **agent 仓库全量文档总索引**，按用途分类，每篇一句话说明。

## 快速上手

| 想做什么 | 看这篇 |
|---------|--------|
| 了解项目是什么、有哪些能力 | [项目 README](README.md) |
| 从零克隆到启动（快速指南） | [仓库根快速开始](docs/quick-start.md) |
| 快速启动（一键容器化） | [部署指南](ragent/docs/deployment.md) |
| 查看全部接口 | [API 文档](ragent/docs/api.md) |
| 演示 5 个典型工业问题 | [演示 Query 集](ragent/docs/demo_queries.md) |

## 文档目录

```
agent/  (仓库根目录)
├── README.md               # 项目主文档(核心能力、架构、快速开始)
├── DOCUMENTATION.md        # 文档导航(本页)
├── DEVELOPMENT.md          # AI 开发协作规范与 7 步工作流
├── docker-compose.yml      # 一键容器化编排(9 服务)
├── docs/
│   └── quick-start.md      # 从零克隆 → 启动基础设施 → 编译运行
├── milvus/                 # Milvus 独立部署配置
└── ragent/
    ├── CHANGELOG.md        # 版本变更日志
    ├── Dockerfile          # 后端容器化
    ├── frontend/
    │   └── TESTING.md      # 前端测试说明
    └── docs/
        ├── architecture.md         # 架构文档
        ├── api.md                  # API 文档
        ├── deployment.md           # 部署指南(一键容器化)
        ├── resume-project.md       # 简历项目描述
        ├── demo_queries.md         # 演示 Query 集
        ├── multi-channel-retrieval.md  # 多通道检索架构
        ├── refactoring-summary.md  # 多通道检索重构总结
        ├── examples/
        │   └── pdf-ingestion-example.md  # PDF 入库完整示例
        ├── skills/
        │   └── ragent-dev-workflow/      # 开发流程 Skill 说明
        ├── eval/                   # 权威评测结论
        │   ├── phase0-baseline-report.md # Phase 0 真实检索基线与评测口径
        │   └── r5-restricted-conclusions.md # R5 总评测限制性结论(简历指标口径)
        └── archive/                # 历史开发与修复记录
            ├── phase1-dag-routing.md # Phase 1-A ETL 显式边、DAG 路由
            ├── phase2-hyperedge-ingestion.md # Phase 2 超边入库
            ├── phase3-entity-normalization.md # Phase 3 实体归一化
            ├── phase-8.2c-evaluation-blockers.md # 8.2-C 评测阻塞记录
            ├── r0-e-1-retrieval-reliability.md # R0-E-1 检索可靠性
            ├── r0-e-baseline-blocker.md # R0-E 基线阻塞
            ├── r0-e-execution-reliability-repair.md # R0-E 执行可靠性修复
            └── r4-image-collection-checklist.md # R4 图像收集清单
```

## 按分类索引

### 项目入口
| 文档 | 功能 |
|------|------|
| [README.md](README.md) | 项目主文档:核心能力、技术架构、快速开始 |
| [ragent/CHANGELOG.md](ragent/CHANGELOG.md) | 版本变更日志(v1.0 / v2.0 / Unreleased) |
| [DEVELOPMENT.md](DEVELOPMENT.md) | AI 开发协作规范与 7 步工作流 |

### 快速开始与部署
| 文档 | 功能 |
|------|------|
| [docs/quick-start.md](docs/quick-start.md) | 从零克隆 → 启动全部基础设施 → 编译运行后端 → 打开前端 |
| [ragent/docs/deployment.md](ragent/docs/deployment.md) | 一键容器化部署、数据持久化、端口拓扑、故障排查 |
| `ragent/resources/docker/lightweight/README.md` | 低配环境轻量部署(内存限制) |

### 技术设计
| 文档 | 功能 |
|------|------|
| [ragent/docs/architecture.md](ragent/docs/architecture.md) | 系统全景图、模块划分、数据流、扩展点、数据模型 |
| [ragent/docs/api.md](ragent/docs/api.md) | 17 个 Controller 接口、SSE 协议、LLM 路由、Milvus Schema |
| [ragent/docs/multi-channel-retrieval.md](ragent/docs/multi-channel-retrieval.md) | 多通道检索引擎设计(向量/意图/关键词/图像/超图 + 后处理链) |
| [ragent/docs/refactoring-summary.md](ragent/docs/refactoring-summary.md) | 多通道检索架构重构的目标与内容 |
| [ragent/docs/examples/pdf-ingestion-example.md](ragent/docs/examples/pdf-ingestion-example.md) | PDF 入库 Pipeline 完整示例 |

### 评测与面试
| 文档 | 功能 |
|------|------|
| [ragent/docs/eval/r5-restricted-conclusions.md](ragent/docs/eval/r5-restricted-conclusions.md) | R5 总评测权威结论:检索 Hit@1 63.83%、MRR 0.6944、RAGAS 60 条、简历指标口径与复现映射 |
| [ragent/docs/resume-project.md](ragent/docs/resume-project.md) | 简历 4 条工作内容、量化指标、面试应答 |
| [ragent/docs/demo_queries.md](ragent/docs/demo_queries.md) | 5 个典型工业演示问题(故障/工艺/图纸) |

### 评测结论
| 文档 | 功能 |
|------|------|
| [ragent/docs/eval/phase0-baseline-report.md](ragent/docs/eval/phase0-baseline-report.md) | Phase 0 真实检索基线、样本范围和自动评测结果 |
| [ragent/docs/eval/r5-restricted-conclusions.md](ragent/docs/eval/r5-restricted-conclusions.md) | R5 总评测限制性结论：简历指标口径与复现映射 |

### 历史归档
| 文档 | 功能 |
|------|------|
| [ragent/docs/archive/phase1-dag-routing.md](ragent/docs/archive/phase1-dag-routing.md) | Phase 1 ETL：显式 DAG 路由、安全重试、幂等写入 |
| [ragent/docs/archive/phase2-hyperedge-ingestion.md](ragent/docs/archive/phase2-hyperedge-ingestion.md) | Phase 2 超边抽取与持久化 |
| [ragent/docs/archive/phase3-entity-normalization.md](ragent/docs/archive/phase3-entity-normalization.md) | Phase 3 工业实体归一化 |
| [ragent/docs/archive/phase-8.2c-evaluation-blockers.md](ragent/docs/archive/phase-8.2c-evaluation-blockers.md) | 8.2-C 评测阻塞与修复记录 |
| [ragent/docs/archive/r0-e-1-retrieval-reliability.md](ragent/docs/archive/r0-e-1-retrieval-reliability.md) | R0-E-1 检索预算与取消传播 |
| [ragent/docs/archive/r0-e-baseline-blocker.md](ragent/docs/archive/r0-e-baseline-blocker.md) | R0-E 受控基线阻塞 |
| [ragent/docs/archive/r0-e-execution-reliability-repair.md](ragent/docs/archive/r0-e-execution-reliability-repair.md) | R0-E 执行可靠性修复 |
| [ragent/docs/archive/r4-image-collection-checklist.md](ragent/docs/archive/r4-image-collection-checklist.md) | R4 工业图像素材收集清单 |

### 前端测试
| 文档 | 功能 |
|------|------|
| [ragent/frontend/TESTING.md](ragent/frontend/TESTING.md) | 前端测试说明 |

### 知识库示例数据
> 以下为系统演示/评测使用的业务知识文档（系统入库数据，非项目文档）：
> `ragent/resources/docs/knowledge/` 下按 `biz-ins` / `biz-oa` / `group-finance` / `group-hr` / `group-it` 分组，含人事制度、招聘信息、薪资福利、开票信息、IT 支持、OA/保险数据安全规范等。

## 推荐阅读顺序

**面试/学习**:README → architecture → api → multi-channel-retrieval → eval/r5-restricted-conclusions → resume-project

**部署/演示**:docs/quick-start → ragent/docs/deployment → demo_queries

**评测结论**:ragent/docs/eval/r5-restricted-conclusions → eval/phase0-baseline-report
