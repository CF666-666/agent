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

> **首次使用请先阅读 `DEVELOPMENT.md`**（仓库根目录）获取完整项目上下文。

## 快速检查清单（每次编码前）

- [ ] 已读取 `DEVELOPMENT.md` 了解项目背景和技术约束
- [ ] 已查看 `ragent/docs/upgrade/dev-roadmap.md` 确认当前 Phase 和闭环
- [ ] 已确认要修改的代码只在扩展点接入（不修改已有核心逻辑）

## 执行规则

参见仓库根目录 `DEVELOPMENT.md` 的完整流程。核心约束：

1. **方案先行** → 等用户确认后编码
2. **最小闭环** → 独立可测，不依赖后面未写的代码
3. **先自测** → `mvnw compile -pl bootstrap -am -DskipTests` 必须通过
4. **等 review** → 用户说"通过"才能进下一闭环
5. **同步 roadmap** → 每闭环完成更新 `ragent/docs/upgrade/dev-roadmap.md`

## 关键文件索引

| 用途 | 路径 |
|------|------|
| 开发规范（通用） | `DEVELOPMENT.md`（仓库根目录） |
| 路线图 & 进度 | `ragent/docs/upgrade/dev-roadmap.md` |
| 技术方案 | `ragent/docs/upgrade/upgrade-plan.md` |
| 源码排查 | `ragent/docs/upgrade/source-checklist.md` |
| 快速启动 | `docs/quick-start.md` |
| 主 POM | `ragent/bootstrap/pom.xml` |
| 应用配置 | `ragent/bootstrap/src/main/resources/application.yaml` |
