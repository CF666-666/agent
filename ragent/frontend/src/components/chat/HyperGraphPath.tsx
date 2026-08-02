import * as React from "react";
import { Network, ChevronRight } from "lucide-react";

import type { Reference } from "@/types";

interface HyperGraphPathProps {
  reference: Reference;
}

function splitPath(detail?: string | null): string[] {
  if (!detail) return [];
  return detail
    .split("→")
    .map((part) => part.trim())
    .filter(Boolean);
}

export const HyperGraphPath = React.memo(function HyperGraphPath({
  reference
}: HyperGraphPathProps) {
  const steps = splitPath(reference.detail);
  const score =
    typeof reference.extra?.score === "number" ? reference.extra.score.toFixed(3) : null;

  return (
    <div className="flex gap-3 rounded-lg border border-[#E2E8F0] bg-white p-3">
      <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-[#FEF3C7]">
        <Network className="h-4 w-4 text-[#D97706]" />
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium text-[#1E3A5F]">
            {reference.label || "推理路径"}
          </span>
          {score ? (
            <span className="rounded-full bg-[#FEF3C7] px-2 py-0.5 text-xs text-[#B45309]">
              匹配 {score}
            </span>
          ) : null}
        </div>
        {steps.length > 0 ? (
          <div className="mt-2 flex flex-wrap items-center gap-1">
            {steps.map((step, index) => (
              <React.Fragment key={`${step}-${index}`}>
                {index > 0 ? (
                  <ChevronRight className="h-3.5 w-3.5 shrink-0 text-[#94A3B8]" />
                ) : null}
                <span className="rounded-md bg-[#FEF3C7]/60 px-2 py-0.5 text-xs font-medium text-[#92400E]">
                  {step}
                </span>
              </React.Fragment>
            ))}
          </div>
        ) : null}
        {reference.snippet ? (
          <p className="mt-2 line-clamp-2 text-xs leading-relaxed text-[#64748B]">
            {reference.snippet}
          </p>
        ) : null}
      </div>
    </div>
  );
});
