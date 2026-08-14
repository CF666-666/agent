import { useEffect, useState } from "react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import type {
  IngestionPipelineEdge,
  IngestionPipelineNode
} from "@/services/ingestionService";

export interface ParsedGraph {
  name: string;
  description?: string | null;
  nodes: IngestionPipelineNode[];
  edges: IngestionPipelineEdge[];
}

interface PipelineJsonDialogProps {
  open: boolean;
  initialJson: string;
  onOpenChange: (open: boolean) => void;
  onApply: (data: ParsedGraph) => void;
}

export function PipelineJsonDialog({
  open,
  initialJson,
  onOpenChange,
  onApply
}: PipelineJsonDialogProps) {
  const [text, setText] = useState(initialJson);
  const [error, setError] = useState("");

  useEffect(() => {
    if (open) {
      setText(initialJson);
      setError("");
    }
  }, [open, initialJson]);

  const handleApply = () => {
    let parsed: unknown;
    try {
      parsed = JSON.parse(text);
    } catch {
      setError("JSON 格式错误");
      return;
    }
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      setError("顶层必须是对象");
      return;
    }
    const obj = parsed as Record<string, unknown>;
    if (typeof obj.name !== "string" || !obj.name.trim()) {
      setError("缺少 name 字段");
      return;
    }
    if (!Array.isArray(obj.nodes)) {
      setError("nodes 必须是数组");
      return;
    }
    if (obj.edges != null && !Array.isArray(obj.edges)) {
      setError("edges 必须是数组");
      return;
    }
    setError("");
    onApply({
      name: obj.name,
      description: typeof obj.description === "string" ? obj.description : null,
      nodes: obj.nodes as IngestionPipelineNode[],
      edges: (obj.edges ?? []) as IngestionPipelineEdge[]
    });
    onOpenChange(false);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[720px]">
        <DialogHeader>
          <DialogTitle>JSON 高级编辑</DialogTitle>
          <DialogDescription>
            直接编辑完整的节点与连线配置（含画布位置），保存后回填到画布
          </DialogDescription>
        </DialogHeader>
        <Textarea
          value={text}
          onChange={(event) => {
            setText(event.target.value);
            setError("");
          }}
          rows={20}
          className="font-mono text-xs"
          spellCheck={false}
        />
        {error && <p className="text-sm text-red-500">{error}</p>}
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            取消
          </Button>
          <Button onClick={handleApply}>应用</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
