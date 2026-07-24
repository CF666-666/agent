# ragent-dev-workflow

## 是什么

约束 Ragent 项目升级开发的强制工作流，防止开发过程紊乱导致 bug 堆积。

## 支持的工具

| 工具 | 安装方式 |
|------|---------|
| **CodeBuddy** | 已在 `.codebuddy/skills/ragent-dev-workflow/` 下，无需手动安装 |
| **Claude Code** | 复制 `ragent/docs/skills/ragent-dev-workflow/` 到 `~/.claude/skills/` |
| **GitHub Copilot** | 自动读取仓库根目录 `DEVELOPMENT.md` |
| **Cursor** | 自动读取仓库根目录 `DEVELOPMENT.md`，或放在 `.cursor/rules/` |
| **Aider / Codex / 其他** | 自动读取仓库根目录 `DEVELOPMENT.md` |

## 工作原理

```
DEVELOPMENT.md          ← 单一事实来源，仓库根目录，所有 AI Agent 自动发现
    ↓
├── .codebuddy/skills/  ← CodeBuddy 专用包装，指向 DEVELOPMENT.md
├── ~/.claude/skills/   ← Claude Code 专用包装，指向 DEVELOPMENT.md
└── (Copilot/Cursor/Aider 直接读 DEVELOPMENT.md)
```

## 安装（非 CodeBuddy 用户）

```bash
# Claude Code
cp -r ragent/docs/skills/ragent-dev-workflow ~/.claude/skills/

# 其他工具：无需操作，会自动读取仓库根目录的 DEVELOPMENT.md
```

## 触发时机

对 AI 说以下类型的话时自动激活：
- "开始编码 Phase 1"
- "开发第 2 个闭环"
- "实现 PdfBoxParser"
- "按 roadmap 开发下一个模块"
- 任何涉及 Ragent 项目 Java 代码的指令
