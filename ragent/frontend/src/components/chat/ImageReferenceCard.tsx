import * as React from "react";
import { ImageIcon } from "lucide-react";

import { ImageLightbox } from "@/components/chat/ImageLightbox";
import { resolveAssetUrl } from "@/utils/helpers";
import type { Reference } from "@/types";

interface ImageReferenceCardProps {
  reference: Reference;
}

export const ImageReferenceCard = React.memo(function ImageReferenceCard({
  reference
}: ImageReferenceCardProps) {
  const [open, setOpen] = React.useState(false);
  const src = reference.url;
  if (!src) return null;

  const fullUrl = resolveAssetUrl(src);
  const alt = reference.label || "设备图纸";

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="group flex w-full flex-col gap-2 rounded-lg border border-[#E2E8F0] bg-white p-3 text-left transition-colors hover:border-[#93C5FD]"
      >
        <div className="flex items-center gap-2">
          <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-[#F3E8FF]">
            <ImageIcon className="h-4 w-4 text-[#9333EA]" />
          </div>
          <span className="truncate text-sm font-medium text-[#1E3A5F]">
            {reference.label || "设备图纸"}
          </span>
        </div>
        <div className="relative h-32 w-full overflow-hidden rounded-md bg-[#F1F5F9]">
          <img
            src={fullUrl}
            alt={alt}
            className="h-full w-full object-cover transition-transform duration-200 group-hover:scale-105"
            loading="lazy"
          />
          <span className="absolute bottom-1.5 right-1.5 rounded bg-black/60 px-1.5 py-0.5 text-[10px] text-white">
            点击预览
          </span>
        </div>
      </button>
      <ImageLightbox src={src} alt={alt} open={open} onOpenChange={setOpen} />
    </>
  );
});
