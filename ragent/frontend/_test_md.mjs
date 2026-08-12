import fs from "node:fs";
import { unified } from "unified";
import remarkParse from "remark-parse";
import remarkGfm from "remark-gfm";

const raw = fs.readFileSync("d:/Java/agent/scripts/_real_msg.txt", "utf-8").trim();

function normalizeAtxHeadings(text) {
  if (!text) return text;
  return text.replace(/(^|\n)(#{1,6})([^\s#])/g, "$1$2 $3");
}

const normalized = normalizeAtxHeadings(raw);

console.log("=== 原始片段 ===");
console.log(JSON.stringify(raw.slice(0, 150)));
console.log("=== 规范化片段 ===");
console.log(JSON.stringify(normalized.slice(0, 150)));

function extractTree(text) {
  const mdast = unified().use(remarkParse).use(remarkGfm).parse(text);
  function summarize(node, depth) {
    const indent = "  ".repeat(depth);
    const value = typeof node.value === "string" ? node.value.slice(0, 60) : "";
    console.log(`${indent}- ${node.type}${value ? `: ${JSON.stringify(value)}` : ""}`);
    if (node.children) {
      for (const c of node.children) summarize(c, depth + 1);
    }
  }
  summarize(mdast, 0);
}

console.log("\n=== 原始树（前 3 层）===");
extractTree(raw);
console.log("\n=== 规范化树（前 3 层）===");
extractTree(normalized);
