// 验证 collapseOverlongStrong 行为：长强语义降级，短强语义保留
function normalizeAtxHeadings(text) {
  if (!text) return text;
  return text.replace(/(^|\n)(#{1,6})([^\s#])/g, "$1$2 $3");
}
const LONG_STRONG_TEXT_LIMIT = 32;
function collapseOverlongStrong(text) {
  if (!text) return text;
  return text.replace(/\*\*([\s\S]+?)\*\*/g, (m, inner) => {
    if (inner.length > LONG_STRONG_TEXT_LIMIT) return inner;
    return m;
  });
}

const cases = [
  { input: "**结构组成**：主梁", expectShort: true },
  { input: "**桥式起重机**（也称行车）", expectShort: true },
  { input: "**我是公司内部的技术设备维护助手，主要负责解答关于各类工业设备的定期检查、维护保养等问题。**", expectShort: false }
];

for (const c of cases) {
  const out = collapseOverlongStrong(normalizeAtxHeadings(c.input));
  const stillBold = out.includes("**");
  console.log(`input  = ${c.input.slice(0, 60)}${c.input.length > 60 ? "..." : ""}`);
  console.log(`output = ${out.slice(0, 60)}${out.length > 60 ? "..." : ""}`);
  console.log(`still bold (markdown)? = ${stillBold}; expected short-bold=${c.expectShort}; ${stillBold === c.expectShort ? "PASS" : "FAIL"}`);
  console.log();
}
