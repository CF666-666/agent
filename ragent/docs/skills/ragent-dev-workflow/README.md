# ragent-dev-workflow Skill

## 是什么

约束 Ragent 项目升级开发的强制工作流 Skill，确保代码质量、避免开发过程紊乱导致 bug 堆积。

## 做什么

- **方案先行**：写代码前先简述设计方案
- **最小闭环**：按依赖关系拆成小闭环，每个闭环独立可测
- **自测 gate**：编码后必须自测通过才给用户 review
- **review gate**：用户确认后才能进下一闭环
- **同步 roadmap**：每完成一个闭环更新 dev-roadmap

## 安装

复制到 Claude Skills 目录：

```bash
# 将这个 skill 目录复制到 Claude Skills 目录
cp -r ragent/docs/skills/ragent-dev-workflow ~/.claude/skills/
```

或者 Windows：

```powershell
Copy-Item -Recurse ragent/docs/skills/ragent-dev-workflow $env:USERPROFILE\.claude\skills\ragent-dev-workflow
```

安装后重启 Claude Code 即可生效。

## 触发时机

当你对 Claude 说以下类型的话时，该 Skill 自动激活：

- "开始编码 Phase 1"
- "开发第 2 个闭环"
- "实现 PdfBoxParser"
- "按 roadmap 开发下一个模块"
- 任何涉及 ragent 项目 Java 代码编写/修改的指令
