import * as React from "react";
import { BookOpen } from "lucide-react";

import { HyperGraphPath } from "@/components/chat/HyperGraphPath";
import { ImageReferenceCard } from "@/components/chat/ImageReferenceCard";
import { TextReferenceCard } from "@/components/chat/TextReferenceCard";
import type { Reference, ReferenceType } from "@/types";

export type ReferenceFilter = ReferenceType | "ALL";

interface ReferencesPanelProps {
  references: Reference[];
  filter: ReferenceFilter;
  onFilterChange: (filter: ReferenceFilter) => void;
}

const FILTER_OPTIONS: { value: ReferenceFilter; label: string }[] = [
  { value: "ALL", label: "全部" },
  { value: "TEXT", label: "文本" },
  { value: "IMAGE", label: "图纸" },
  { value: "HYPERGRAPH", label: "推理路径" }
];

export const ReferencesPanel = React.memo(function ReferencesPanel({
  references,
  filter,
  onFilterChange
}: ReferencesPanelProps) {
  if (!references || references.length === 0) return null;

  // 引用为一次性全量到达，用类型 + 索引作为稳定 key
  const textRefs: Reference[] = [];
  const imageRefs: Reference[] = [];
  const hyperRefs: Reference[] = [];
  for (const ref of references) {
    if (filter !== "ALL" && ref.type !== filter) continue;
    if (ref.type === "TEXT") textRefs.push(ref);
    else if (ref.type === "IMAGE") imageRefs.push(ref);
    else if (ref.type === "HYPERGRAPH") hyperRefs.push(ref);
  }
  const filteredCount = textRefs.length + imageRefs.length + hyperRefs.length;

  // 计算各类型实际数量用于 Tab 徽标
  const countOf = (type: ReferenceType) => references.filter((ref) => ref.type === type).length;

  return (
    <div className="mt-4 space-y-2">
      <div className="flex items-center gap-2">
        <BookOpen className="h-4 w-4 text-[#64748B]" />
        <span className="text-xs font-semibold uppercase tracking-wide text-[#64748B]">
          检索来源
        </span>
      </div>

      {/* 来源过滤标签栏 */}
      <div className="flex flex-wrap items-center gap-1.5">
        {FILTER_OPTIONS.map((option) => {
          const active = filter === option.value;
          const count =
            option.value === "ALL"
              ? references.length
              : countOf(option.value as ReferenceType);
          return (
            <button
              key={option.value}
              type="button"
              onClick={() => onFilterChange(option.value)}
              className={[
                "inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-xs font-medium transition-colors",
                active
                  ? "bg-[#2563EB] text-white"
                  : "bg-[#F1F5F9] text-[#64748B] hover:bg-[#E2E8F0]"
              ].join(" ")}
            >
              {option.label}
              <span
                className={[
                  "rounded-full px-1 text-[10px]",
                  active ? "bg-white/25 text-white" : "bg-white text-[#94A3B8]"
                ].join(" ")}
              >
                {count}
              </span>
            </button>
          );
        })}
      </div>

      {/* 引用列表：按类型分组平铺（B 决策：默认展开） */}
      {filteredCount === 0 ? (
        <p className="rounded-lg border border-dashed border-[#E2E8F0] px-3 py-4 text-center text-xs text-[#94A3B8]">
          当前筛选条件下暂无引用
        </p>
      ) : (
        <div className="grid gap-2">
          {textRefs.map((ref, index) => (
            <TextReferenceCard key={`text-${index}`} reference={ref} />
          ))}
          {imageRefs.map((ref, index) => (
            <ImageReferenceCard key={`image-${index}`} reference={ref} />
          ))}
          {hyperRefs.map((ref, index) => (
            <HyperGraphPath key={`hyper-${index}`} reference={ref} />
          ))}
        </div>
      )}
    </div>
  );
});
