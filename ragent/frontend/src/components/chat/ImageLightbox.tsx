import * as React from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { AlertTriangle, Loader2, X } from "lucide-react";

import { resolveAssetUrl } from "@/utils/helpers";
import { cn } from "@/lib/utils";

interface ImageLightboxProps {
  src: string;
  alt: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

type ImageState = "loading" | "loaded" | "error";

export const ImageLightbox = React.memo(function ImageLightbox({
  src,
  alt,
  open,
  onOpenChange
}: ImageLightboxProps) {
  const fullUrl = resolveAssetUrl(src);
  const [imgState, setImgState] = React.useState<ImageState>("loading");
  const [retryNonce, setRetryNonce] = React.useState(0);

  React.useEffect(() => {
    if (open) {
      setImgState("loading");
    }
  }, [open, fullUrl]);

  React.useEffect(() => {
    if (!open) return;
    if (typeof window === "undefined") return;
    if (!import.meta.env.DEV) return;
    // eslint-disable-next-line no-console
    console.debug("[ImageLightbox] preview asset url =", fullUrl);
  }, [open, fullUrl]);

  const handleRetry = React.useCallback(() => {
    setRetryNonce((value) => value + 1);
    setImgState("loading");
  }, []);

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-black/80 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 max-h-[90vh] max-w-[90vw] -translate-x-1/2 -translate-y-1/2 overflow-hidden rounded-xl bg-white shadow-2xl data-[state=open]:animate-in data-[state=open]:zoom-in-95">
          <div className="relative bg-[#0F172A]">
            {imgState !== "error" ? (
              <img
                key={`${fullUrl}::${retryNonce}`}
                src={fullUrl}
                alt={alt}
                className={cn(
                  "max-h-[85vh] w-auto max-w-full object-contain",
                  imgState === "loading" && "opacity-0"
                )}
                decoding="async"
                onLoad={() => setImgState("loaded")}
                onError={() => {
                  if (typeof window !== "undefined") {
                    // eslint-disable-next-line no-console
                    console.warn("[ImageLightbox] 预览图加载失败", {
                      rawSrc: src,
                      resolvedSrc: fullUrl
                    });
                  }
                  setImgState("error");
                }}
              />
            ) : (
              <div className="flex max-h-[85vh] min-h-[40vh] min-w-[60vw] flex-col items-center justify-center gap-3 px-10 text-center text-rose-100">
                <AlertTriangle className="h-8 w-8 text-rose-300" />
                <div className="text-base font-medium">图纸加载失败</div>
                <div className="max-w-[80vw] truncate text-xs text-rose-200/80" title={fullUrl}>
                  {fullUrl}
                </div>
                <button
                  type="button"
                  onClick={handleRetry}
                  className="rounded border border-rose-200/40 px-3 py-1 text-xs text-rose-100 transition-colors hover:bg-rose-500/20"
                >
                  重新加载
                </button>
              </div>
            )}
            {imgState === "loading" ? (
              <div className="absolute inset-0 flex items-center justify-center text-white/60">
                <Loader2 className="h-8 w-8 animate-spin" />
              </div>
            ) : null}
            <Dialog.Close asChild>
              <button
                type="button"
                className="absolute right-3 top-3 flex h-8 w-8 items-center justify-center rounded-full bg-black/60 text-white transition-colors hover:bg-black/80"
                aria-label="关闭预览"
              >
                <X className="h-4 w-4" />
              </button>
            </Dialog.Close>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
});
