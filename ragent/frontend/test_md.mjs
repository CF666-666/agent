// js module using bundled packages
import { unified } from "unified";
import remarkParse from "remark-parse";
import remarkGfm from "remark-gfm";

const tests = [
  "**一段文字**",
  "###我是专注于工业设备的助手。\n我的知识库...",
  "**你是什么助手**我是专注于工业设备维护与安全操作的内部助手...",
  "**结构组成**：主梁\n**作业环境**：厂房",
  "###一、起重机根据文档中的图纸描述，起重机（特指桥式起重机，俗称\"行车\"）是一种在大型工业厂房内用于起重和吊运作业的设备。\n\n其典型特征和作业环境包括："
];

for (const t of tests) {
  console.log("=== INPUT ===");
  console.log(t);
  console.log("--- AST ---");
  const mdast = unified().use(remarkParse).use(remarkGfm).parse(t);
  function dump(n, indent = 0) {
    console.log(" ".repeat(indent) + n.type + (n.value ? ": " + JSON.stringify(n.value.slice(0, 40)) : ""));
    if (n.children) for (const c of n.children) dump(c, indent + 2);
  }
  dump(mdast);
  console.log();
}
