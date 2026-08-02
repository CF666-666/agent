import * as React from "react";
import { FileText } from "lucide-react";

import type { Reference } from "@/types";

interface TextReferenceCardProps {
  reference: Reference;
}

function formatScore(reference: Reference): string | null {
  const raw = reference.extra?.score;
  if (typeof raw !== "number") return null;
  return raw.toFixed(3);
}

export const TextReferenceCard = React.memo(function TextReferenceCard({
  reference
}: TextReferenceCardProps) {
  const score = formatScore(reference);
  return (
    <div className="flex gap-3 rounded-lg border border-[#E2E8F0] bg-white p-3 transition-colors hover:border-[#93C5FD]">
      <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-[#DBEAFE]">
        <FileText className="h-4 w-4 text-[#2563EB]" />
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium text-[#1E3A5F]">{reference.label || "文本引用"}</span>
          {score !== null ? (
            <span className="rounded-full bg-[#E0E7FF] px-2 py-0.5 text-xs text-[#4F46E5]">
              匹配 {score}
            </span>
          ) : null}
        </div>
        {reference.snippet ? (
          <p className="mt-1 line-clamp-3 text-sm leading-relaxed text-[#475569]">
            {reference.snippet}
          </p>
        ) : null}
      </div>
    </div>
  );
});
