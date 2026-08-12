// 用 mdsicle 微项目模拟整段加粗
import { unified } from "unified";
import remarkParse from "remark-parse";
import remarkGfm from "remark-gfm";
import { visit } from "unist-util-visit";

const tests = [
  // 1. 直接 `**` 包裹
  "**一段文字**",
  // 2. 不带空格的 `###标题`
  "###我是专注于工业设备的助手。\n我的知识库...\n\n###一、起重机...",
  // 3. 末尾连续 `**`
  "我是个助手。**关键术语: 高炉、轧机。**",
  // 4. 整段以 `**:` 开头
  "**你是什么助手**我是专注于工业设备维护与安全操作的内部助手...",
  // 5. 一系列 `**碎片**` 连续
  "**结构组成**：主梁\n**作业环境**：厂房"
];

for (const t of tests) {
  console.log("=== INPUT ===");
  console.log(JSON.stringify(t));
  const mdast = unified().use(remarkParse).use(remarkGfm).parse(t);
  function dump(n, indent = 0) {
    console.log(" ".repeat(indent) + n.type + (n.value ? ": " + JSON.stringify(n.value.slice(0, 30)) : ""));
    if (n.children) for (const c of n.children) dump(c, indent + 2);
  }
  dump(mdast);
  console.log();
}
