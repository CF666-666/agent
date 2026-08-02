import * as React from "react";
import { ArrowLeft, Bot, Boxes, Cpu, GitBranch, Network, Search, Sparkles } from "lucide-react";
import { Link } from "react-router-dom";

interface PipelineItem {
  title: string;
  desc: string;
  icon: React.ReactNode;
}

interface NameMeaning {
  letter: string;
  word: string;
  meaning: string;
}

const NAME_MEANINGS: NameMeaning[] = [
  { letter: "H", word: "Hypergraph", meaning: "超图 — 以超边建模设备故障间的多元关联，突破二元图谱表达局限" },
  { letter: "I", word: "Industrial", meaning: "工业级 — 面向钢铁/石化/电力等工业场景，追求可靠、可解释" },
  { letter: "M", word: "Multimodal", meaning: "多模态 — 融合文本、设备图纸、推理路径三类异构检索信号" },
  { letter: "RAG", word: "Retrieval-Augmented Generation", meaning: "检索增强生成 — 先检索后生成，答案有据可依" },
  { letter: "Agent", word: "Agent", meaning: "智能体 — 具备意图理解、多源检索、融合决策的完整智能问答体" }
];

interface TechStackItem {
  name: string;
  desc: string;
}

const TECH_STACK: TechStackItem[] = [
  { name: "Spring Boot 3", desc: "微服务骨架，分层清晰（bootstrap/framework/infra-ai）" },
  { name: "Milvus", desc: "开源向量数据库，管理 4096 维文本/图像向量" },
  { name: "Qwen-VL / Qwen3-Embedding", desc: "多模态视觉理解 + 语义嵌入（SiliconFlow 云端 API）" },
  { name: "BaiLian Rerank", desc: "检索后精排，多源候选 Top-K 收敛" },
  { name: "超图引擎", desc: "自研内存超边引擎（倒排索引），建模设备-工况-参数-故障多元关联" },
  { name: "React 18 + TS + Zustand", desc: "SSE 流式对话前端，多模态引用渲染" },
  { name: "RocketMQ / PostgreSQL / Redis", desc: "异步文档处理、业务数据、会话缓存" }
];

const PIPELINE: PipelineItem[] = [
  {
    title: "意图理解",
    desc: "识别工业场景查询意图，规划检索策略",
    icon: <Cpu className="h-5 w-5" />
  },
  {
    title: "三路检索",
    desc: "文本向量 + 图像语义 + 超图推理并行召回",
    icon: <Search className="h-5 w-5" />
  },
  {
    title: "多源融合",
    desc: "min-max 归一化 + 加权融合跨源候选",
    icon: <GitBranch className="h-5 w-5" />
  },
  {
    title: "精排",
    desc: "Rerank 收敛 Top-K，保留证据链",
    icon: <Sparkles className="h-5 w-5" />
  },
  {
    title: "增强生成",
    desc: "LLM 基于融合上下文生成带引用的答案",
    icon: <Bot className="h-5 w-5" />
  }
];

const SCENARIOS: string[] = [
  "设备故障诊断（高炉/精馏塔/汽轮机/换热器/变压器）",
  "工业 SOP 查询与操作规程问答",
  "图纸检索与视觉理解（设备图纸定位）",
  "故障链推理（超图路径溯源）"
];

export default function AboutPage() {
  return (
    // body 全局 overflow:hidden（聊天布局），此处自建滚动容器
    <div className="h-screen overflow-y-auto bg-gradient-to-b from-[#F8FAFC] to-white">
      {/* 顶部导航 */}
      <header className="sticky top-0 z-10 border-b border-[#E2E8F0] bg-white/80 backdrop-blur">
        <div className="mx-auto flex max-w-4xl items-center justify-between px-6 py-4">
          <div className="flex items-center gap-2">
            <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-[#3B82F6]">
              <Bot className="h-4 w-4 text-white" />
            </div>
            <span className="font-semibold text-[#1A1A1A]">HIRAGent</span>
          </div>
          <Link
            to="/"
            className="inline-flex items-center gap-1.5 rounded-lg border border-[#E2E8F0] bg-white px-3 py-1.5 text-sm text-[#475569] transition-colors hover:bg-[#F1F5F9]"
          >
            <ArrowLeft className="h-4 w-4" />
            返回问答
          </Link>
        </div>
      </header>

      <main className="mx-auto max-w-4xl px-6 py-10">
        {/* Hero */}
        <section className="text-center">
          <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-2xl bg-gradient-to-br from-[#3B82F6] to-[#8B5CF6] shadow-lg">
            <Boxes className="h-8 w-8 text-white" />
          </div>
          <h1 className="mt-6 text-4xl font-bold tracking-tight text-[#0F172A]">
            HIRAGent
          </h1>
          <p className="mt-2 text-lg font-medium text-[#3B82F6]">
            Hypergraph-Integrated Multimodal Industrial RAG Agent
          </p>
          <p className="mt-3 text-sm text-[#64748B]">
            念法：Hi-RAG-ent（"嗨-拉真特"）— 一个用"打招呼的亲切感"承载工业知识问答的多模态智能体
          </p>
        </section>

        {/* 名称含义 */}
        <section className="mt-12">
          <h2 className="text-xl font-semibold text-[#0F172A]">名称含义</h2>
          <div className="mt-4 space-y-3">
            {NAME_MEANINGS.map((item) => (
              <div
                key={item.letter}
                className="flex items-start gap-4 rounded-xl border border-[#E2E8F0] bg-white p-4"
              >
                <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg bg-[#DBEAFE] text-sm font-bold text-[#2563EB]">
                  {item.letter}
                </div>
                <div>
                  <p className="font-medium text-[#1E293B]">{item.word}</p>
                  <p className="mt-0.5 text-sm text-[#64748B]">{item.meaning}</p>
                </div>
              </div>
            ))}
          </div>
        </section>

        {/* 项目定位 */}
        <section className="mt-12">
          <h2 className="text-xl font-semibold text-[#0F172A]">项目定位</h2>
          <p className="mt-3 leading-relaxed text-[#475569]">
            HIRAGent 是一个面向工业场景的<b className="text-[#1E293B]">多模态检索增强生成（RAG）问答系统</b>。
            它把设备的<b className="text-[#1E293B]">规程文本</b>、<b className="text-[#1E293B]">图纸视觉描述</b>和
            <b className="text-[#1E293B]">故障推理超图</b>三类异构知识统一接入检索链路，
            让用户用自然语言提问即可获得<b className="text-[#1E293B]">有据可依、可溯源</b>的诊断建议。
          </p>
          <div className="mt-4 rounded-xl border border-[#DBEAFE] bg-[#EFF6FF] p-4">
            <p className="text-sm text-[#1E40AF]">
              一句话概括：给工业知识装上一个"会检索、会看图、会推理"的智能大脑。
            </p>
          </div>
        </section>

        {/* 应用场景 */}
        <section className="mt-12">
          <h2 className="text-xl font-semibold text-[#0F172A]">应用场景</h2>
          <div className="mt-4 grid gap-3 sm:grid-cols-2">
            {SCENARIOS.map((scenario) => (
              <div
                key={scenario}
                className="rounded-xl border border-[#E2E8F0] bg-white p-4 text-sm text-[#475569]"
              >
                <span className="mr-2 text-[#3B82F6]">◆</span>
                {scenario}
              </div>
            ))}
          </div>
        </section>

        {/* 技术栈 */}
        <section className="mt-12">
          <h2 className="text-xl font-semibold text-[#0F172A]">技术栈</h2>
          <div className="mt-4 grid gap-3 sm:grid-cols-2">
            {TECH_STACK.map((item) => (
              <div key={item.name} className="rounded-xl border border-[#E2E8F0] bg-white p-4">
                <p className="font-medium text-[#1E293B]">{item.name}</p>
                <p className="mt-1 text-sm text-[#64748B]">{item.desc}</p>
              </div>
            ))}
          </div>
        </section>

        {/* 问答流水线（一行横向展示） */}
        <section className="mt-12">
          <h2 className="text-xl font-semibold text-[#0F172A]">问答流水线</h2>
          <div className="mt-4 flex flex-wrap items-stretch gap-2">
            {PIPELINE.map((step, index) => (
              <React.Fragment key={step.title}>
                <div className="flex min-w-[130px] flex-1 flex-col gap-1.5 rounded-xl border border-[#E2E8F0] bg-white p-3">
                  <div className="flex items-center gap-1.5">
                    <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-[#3B82F6] text-white">
                      {step.icon}
                    </span>
                    <span className="text-xs font-medium text-[#1E293B]">{step.title}</span>
                  </div>
                  <p className="text-xs leading-relaxed text-[#64748B]">{step.desc}</p>
                </div>
                {index < PIPELINE.length - 1 ? (
                  <div className="hidden items-center text-[#94A3B8] sm:flex">→</div>
                ) : null}
              </React.Fragment>
            ))}
          </div>
        </section>

        {/* 架构示意 */}
        <section className="mt-12 mb-8">
          <h2 className="text-xl font-semibold text-[#0F172A]">架构示意</h2>
          <div className="mt-4 rounded-xl border border-[#E2E8F0] bg-white p-5">
            <div className="flex flex-wrap items-center justify-center gap-2 text-xs">
              <span className="rounded-lg bg-[#DBEAFE] px-3 py-1.5 font-medium text-[#1E40AF]">
                多模态文档
              </span>
              <span className="text-[#94A3B8]">→</span>
              <span className="rounded-lg bg-[#FEF3C7] px-3 py-1.5 font-medium text-[#92400E]">
                解析 / 分块 / 嵌入
              </span>
              <span className="text-[#94A3B8]">→</span>
              <span className="rounded-lg bg-[#DCFCE7] px-3 py-1.5 font-medium text-[#166534]">
                Milvus + 超图引擎
              </span>
              <span className="text-[#94A3B8]">→</span>
              <span className="rounded-lg bg-[#F3E8FF] px-3 py-1.5 font-medium text-[#6B21A8]">
                三路检索 + 融合 + Rerank
              </span>
              <span className="text-[#94A3B8]">→</span>
              <span className="rounded-lg bg-[#E0E7FF] px-3 py-1.5 font-medium text-[#3730A3]">
                SSE 流式答案 + 引用
              </span>
            </div>
            <div className="mt-4 flex items-center justify-center gap-2 text-xs text-[#94A3B8]">
              <Network className="h-3.5 w-3.5" />
              <span>参考文献 / 图纸预览 / 推理路径 · 全程可追溯</span>
            </div>
          </div>
        </section>
      </main>

      <footer className="border-t border-[#E2E8F0] py-6 text-center text-xs text-[#94A3B8]">
        HIRAGent · Hypergraph-Integrated Multimodal Industrial RAG Agent
      </footer>
    </div>
  );
}
