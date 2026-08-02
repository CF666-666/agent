import * as React from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { X } from "lucide-react";

import { resolveAssetUrl } from "@/utils/helpers";

interface ImageLightboxProps {
  src: string;
  alt: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export const ImageLightbox = React.memo(function ImageLightbox({
  src,
  alt,
  open,
  onOpenChange
}: ImageLightboxProps) {
  const fullUrl = resolveAssetUrl(src);
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-black/80 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 max-h-[90vh] max-w-[90vw] -translate-x-1/2 -translate-y-1/2 overflow-hidden rounded-xl bg-white shadow-2xl data-[state=open]:animate-in data-[state=open]:zoom-in-95">
          <div className="relative">
            <img
              src={fullUrl}
              alt={alt}
              className="max-h-[85vh] w-auto max-w-full object-contain"
            />
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
