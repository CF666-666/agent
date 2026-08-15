import * as React from "react";
import { AlertTriangle, ImageIcon, Loader2 } from "lucide-react";

import { ImageLightbox } from "@/components/chat/ImageLightbox";
import { resolveAssetUrl } from "@/utils/helpers";
import { cn } from "@/lib/utils";
import type { Reference } from "@/types";

interface ImageReferenceCardProps {
  reference: Reference;
}

type ImageState = "loading" | "loaded" | "error";

export const ImageReferenceCard = React.memo(function ImageReferenceCard({
  reference
}: ImageReferenceCardProps) {
  const [open, setOpen] = React.useState(false);
  const [imgState, setImgState] = React.useState<ImageState>("loading");
  const [retryNonce, setRetryNonce] = React.useState(0);
  const src = reference.url;

  // 计算最终请求地址（hooks 顺序固定，不允许提前 return）
  const fullUrl = React.useMemo(() => resolveAssetUrl(src ?? ""), [src]);

  React.useEffect(() => {
    setImgState("loading");
  }, [fullUrl, retryNonce]);

  React.useEffect(() => {
    if (typeof window === "undefined") return;
    if (!import.meta.env.DEV) return;
    if (!src) return;
    // eslint-disable-next-line no-console
    console.debug("[ImageReferenceCard] asset url =", fullUrl, "(raw:", src, ")");
  }, [fullUrl, src]);

  if (!src) return null;
  const alt = reference.label || "设备图纸";

  const handleRetry = React.useCallback(
    (event: React.MouseEvent<HTMLButtonElement>) => {
      event.stopPropagation();
      setRetryNonce((value) => value + 1);
    },
    []
  );

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
        <div
          className={cn(
            "relative h-32 w-full overflow-hidden rounded-md bg-[#F1F5F9]",
            imgState === "error" && "bg-rose-50"
          )}
        >
          {imgState !== "error" ? (
            <img
              key={`${fullUrl}::${retryNonce}`}
              src={fullUrl}
              alt={alt}
              className={cn(
                "h-full w-full object-cover transition-transform duration-200 group-hover:scale-105",
                imgState === "loading" && "opacity-0"
              )}
              loading="lazy"
              decoding="async"
              onLoad={() => setImgState("loaded")}
              onError={() => {
                if (typeof window !== "undefined") {
                  // eslint-disable-next-line no-console
                  console.warn("[ImageReferenceCard] 缩略图加载失败", {
                    reference: reference.label,
                    rawSrc: src,
                    resolvedSrc: fullUrl
                  });
                }
                setImgState("error");
              }}
            />
          ) : (
            <div className="flex h-full w-full flex-col items-center justify-center gap-1 px-3 text-center text-xs text-rose-600">
              <div className="flex items-center gap-1.5 font-medium">
                <AlertTriangle className="h-3.5 w-3.5" />
                缩略图加载失败
              </div>
              <div className="truncate text-[11px] text-rose-500/80" title={fullUrl}>
                {fullUrl}
              </div>
              <button
                type="button"
                onClick={handleRetry}
                className="mt-1 rounded border border-rose-200 px-2 py-0.5 text-[11px] text-rose-600 transition-colors hover:bg-rose-100"
              >
                重试
              </button>
            </div>
          )}
          {imgState === "loading" ? (
            <div className="absolute inset-0 flex items-center justify-center text-[#94A3B8]">
              <Loader2 className="h-5 w-5 animate-spin" />
            </div>
          ) : null}
          {imgState !== "error" ? (
            <span className="absolute bottom-1.5 right-1.5 rounded bg-black/60 px-1.5 py-0.5 text-[10px] text-white">
              点击预览
            </span>
          ) : null}
        </div>
      </button>
      <ImageLightbox src={src} alt={alt} open={open} onOpenChange={setOpen} />
    </>
  );
});
