#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
评测报告 HTML 生成器(Phase 8)

汇总 scripts/eval/report/ 下的评测 JSON,生成一份自包含的 HTML 报告(内联 CSS,无外部依赖),
展示检索指标(Hit Rate / MRR)、口语化 A/B 对比、RAGAS 生成质量指标。

用法:
    python report_html.py [--out report/evaluation_report.html]
"""
import argparse
import datetime
import json
from pathlib import Path

REPORT_DIR = Path(__file__).resolve().parent / "report"


def load(name: str):
    p = REPORT_DIR / name
    if not p.exists():
        return None
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def pct(v) -> str:
    return f"{v * 100:.1f}%"


def metric_card(label: str, value: float, tip: str = "") -> str:
    """指标卡片:标签 + CSS 进度条 + 数值"""
    p = max(0.0, min(value, 1.0)) * 100
    return f"""
      <div class="card metric">
        <div class="m-top">
          <span class="m-label">{label}</span>
          <span class="m-value">{value:.3f}</span>
        </div>
        <div class="m-bar"><div class="m-fill" style="width:{p:.1f}%"></div></div>
        {f'<div class="m-tip">{tip}</div>' if tip else ''}
      </div>"""


def table(headers, rows) -> str:
    th = "".join(f"<th>{h}</th>" for h in headers)
    trs = ""
    for row in rows:
        tds = "".join(f"<td>{c}</td>" for c in row)
        trs += f"<tr>{tds}</tr>"
    return f"<table><thead><tr>{th}</tr></thead><tbody>{trs}</tbody></table>"


def build(retrieval, rewrite_off, rewrite_on, ragas) -> str:
    now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")

    # ---- 检索指标(FAQ 48 条) ----
    ret_html = ""
    if retrieval:
        s = retrieval["summary"]
        hr = s["hit_rate"]
        ret_html = f"""
        <section>
          <h2>检索指标(Hit Rate / MRR)</h2>
          <div class="cards">
            {metric_card("Hit Rate@1", hr["@1"], "Top-1 命中最相关文档的比例")}
            {metric_card("Hit Rate@3", hr["@3"], "Top-3 内命中")}
            {metric_card("Hit Rate@5", hr["@5"], "Top-5 内命中")}
            {metric_card("MRR", s["mrr"], "平均倒数排名,1.0=首个即命中")}
          </div>
          <p class="meta">样本数 {s["total"]} | 未检索到 {s["no_retrieval"]} | 模式 {retrieval.get("mode", "-")}</p>
          <h3>分场景明细</h3>
          {table(
            ["场景", "样本数", "Hit@1", "Hit@3", "Hit@5", "MRR"],
            [[k, v["count"], pct(v["hit_rate"]["@1"]), pct(v["hit_rate"]["@3"]), pct(v["hit_rate"]["@5"]), f"{v['mrr']:.3f}"]
             for k, v in retrieval["summary"]["by_scene"].items()]
          )}
        </section>"""

    # ---- 口语化 A/B 对比 ----
    ab_html = ""
    if rewrite_off and rewrite_on:
        def row(name, d):
            s = d["summary"]
            return [name, pct(s["hit_rate"]["@1"]), pct(s["hit_rate"]["@3"]), f"{s['mrr']:.3f}"]
        ab_html = f"""
        <section>
          <h2>查询重写 A/B 对比(口语化评测集)</h2>
          {table(
            ["模式", "Hit@1", "Hit@3", "MRR"],
            [row("重写关闭(基线)", rewrite_off), row("重写开启", rewrite_on)]
          )}
          <p class="note">说明:单轮口语化场景下意图定向+向量检索基线已达 100%,重写价值主要体现在多轮/指代场景(见报告 §6)。</p>
        </section>"""

    # ---- RAGAS ----
    ragas_html = ""
    if ragas:
        s = ragas["summary"]
        ragas_html = f"""
        <section>
          <h2>RAGAS 生成质量指标</h2>
          <div class="cards">
            {metric_card("答案忠诚度 faithfulness", s["faithfulness"], "回答对检索上下文的忠实程度,不编造")}
            {metric_card("上下文精准度 context_precision", s["context_precision"], "检索上下文中有用信息占比")}
            {metric_card("上下文召回率 context_recall", s["context_recall"], "标准答案信息被上下文覆盖的比例")}
            {metric_card("回答相关性 answer_relevancy", s["answer_relevancy"], "回答与问题的切题程度(参考)")}
          </div>
          <p class="meta">样本数 {s["samples"]} | 打分模型见 report/ragas_report.json</p>
        </section>"""

    html = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Ragent 端到端评测报告</title>
<style>
  :root {{ --primary:#2563eb; --bg:#f1f5f9; --card:#ffffff; --text:#0f172a; --muted:#64748b; }}
  * {{ box-sizing:border-box; margin:0; padding:0; }}
  body {{ font-family:-apple-system,"Segoe UI","Microsoft YaHei",sans-serif; background:var(--bg); color:var(--text); padding:32px 16px; }}
  .wrap {{ max-width:960px; margin:0 auto; }}
  header {{ text-align:center; margin-bottom:28px; }}
  h1 {{ font-size:26px; color:var(--primary); }}
  header p {{ color:var(--muted); margin-top:6px; }}
  section {{ background:var(--card); border-radius:12px; padding:24px; margin-bottom:20px; box-shadow:0 1px 3px rgba(0,0,0,.08); }}
  h2 {{ font-size:18px; margin-bottom:16px; border-left:4px solid var(--primary); padding-left:10px; }}
  h3 {{ font-size:14px; margin:18px 0 8px; color:var(--muted); }}
  .cards {{ display:grid; grid-template-columns:repeat(auto-fit,minmax(180px,1fr)); gap:14px; }}
  .metric {{ padding:14px; border:1px solid #e2e8f0; border-radius:8px; }}
  .m-top {{ display:flex; justify-content:space-between; align-items:baseline; margin-bottom:8px; }}
  .m-label {{ font-size:13px; color:var(--muted); }}
  .m-value {{ font-size:20px; font-weight:700; color:var(--primary); }}
  .m-bar {{ height:8px; background:#e2e8f0; border-radius:4px; overflow:hidden; }}
  .m-fill {{ height:100%; background:linear-gradient(90deg,#60a5fa,#2563eb); border-radius:4px; }}
  .m-tip {{ font-size:11px; color:var(--muted); margin-top:6px; }}
  .meta {{ font-size:12px; color:var(--muted); margin-top:10px; }}
  .note {{ font-size:12px; color:var(--muted); margin-top:8px; }}
  table {{ width:100%; border-collapse:collapse; font-size:13px; margin-top:6px; }}
  th,td {{ padding:8px 10px; text-align:left; border-bottom:1px solid #e2e8f0; }}
  th {{ background:#f8fafc; color:var(--muted); font-weight:600; }}
  footer {{ text-align:center; color:var(--muted); font-size:12px; margin-top:20px; }}
</style>
</head>
<body>
<div class="wrap">
  <header>
    <h1>Ragent 端到端评测报告</h1>
    <p>生成时间:{now} · 脚本 scripts/eval/ · 可一键复现</p>
  </header>
  {ret_html}
  {ab_html}
  {ragas_html}
  <footer>数据来源:scripts/eval/report/*.json · 详见 docs/evaluation-report.md</footer>
</div>
</body>
</html>"""
    return html


def main():
    parser = argparse.ArgumentParser(description="生成 HTML 评测报告")
    parser.add_argument("--out", type=Path, default=REPORT_DIR / "evaluation_report.html")
    args = parser.parse_args()

    # 检索总指标优先用 FAQ 48 条全量报告;口语化 A/B 用 rewrite-off/on
    retrieval = load("retrieval_report.json") or load("retrieval_rewrite-on.json")
    rewrite_off = load("retrieval_rewrite-off.json")
    rewrite_on = load("retrieval_rewrite-on.json")
    ragas = load("ragas_report.json")

    html = build(retrieval, rewrite_off, rewrite_on, ragas)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(html, encoding="utf-8")
    print(f"[report_html] HTML 报告已生成: {args.out}")


if __name__ == "__main__":
    main()
