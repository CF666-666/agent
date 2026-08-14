import { memo } from "react";
import { Handle, Position, useReactFlow, type NodeProps, type Node } from "@xyflow/react";
import { Trash2 } from "lucide-react";
import { cn } from "@/lib/utils";
import type {
  IngestionCondition,
  IngestionNodeExecutionPolicy
} from "@/services/ingestionService";

export interface PipelineCanvasNodeData extends Record<string, unknown> {
  nodeId: string;
  nodeType: string;
  settings?: Record<string, unknown> | null;
  condition?: IngestionCondition | null;
  executionPolicy?: IngestionNodeExecutionPolicy | null;
}

export const NODE_TYPE_LABELS: Record<string, string> = {
  fetcher: "数据获取",
  parser: "文档解析",
  enhancer: "文档增强",
  chunker: "分块",
  enricher: "分块增强",
  indexer: "索引",
  hyperedge_extract: "超边抽取",
  multimodal_parse: "多模态解析"
};

export const NODE_TYPE_COLORS: Record<string, string> = {
  fetcher: "bg-sky-100 text-sky-700",
  parser: "bg-indigo-100 text-indigo-700",
  enhancer: "bg-violet-100 text-violet-700",
  chunker: "bg-amber-100 text-amber-700",
  enricher: "bg-emerald-100 text-emerald-700",
  indexer: "bg-rose-100 text-rose-700",
  hyperedge_extract: "bg-fuchsia-100 text-fuchsia-700",
  multimodal_parse: "bg-cyan-100 text-cyan-700"
};

function PipelineCanvasNodeInner({ id, data }: NodeProps<Node>) {
  const { setNodes, setEdges } = useReactFlow();
  const nodeData = (data ?? {}) as PipelineCanvasNodeData;
  const nodeId = nodeData.nodeId || id;
  const nodeType = nodeData.nodeType || "";
  const label = NODE_TYPE_LABELS[nodeType] || nodeType;
  const color = NODE_TYPE_COLORS[nodeType] || "bg-slate-100 text-slate-700";

  const handleDelete = (event: React.MouseEvent) => {
    event.stopPropagation();
    setNodes((nodes) => nodes.filter((node) => node.id !== id));
    setEdges((edges) => edges.filter((edge) => edge.source !== id && edge.target !== id));
  };

  return (
    <div className="group relative w-48 rounded-lg border border-slate-200 bg-white p-3 shadow-sm transition-shadow hover:shadow-md">
      <Handle
        type="target"
        position={Position.Left}
        className="!h-3 !w-3 !border-2 !border-white !bg-slate-400"
      />
      <div className="flex items-center justify-between gap-2">
        <span className={cn("inline-flex items-center rounded px-2 py-0.5 text-xs font-medium", color)}>
          {label}
        </span>
        <button
          type="button"
          onClick={handleDelete}
          className="hidden rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-red-500 group-hover:block"
          aria-label="删除节点"
          title="删除节点"
        >
          <Trash2 className="h-3.5 w-3.5" />
        </button>
      </div>
      <div className="mt-2 font-mono text-xs text-slate-500">{nodeType}</div>
      <div className="mt-0.5 truncate text-xs text-slate-400" title={nodeId}>
        {nodeId || "（未命名）"}
      </div>
      <Handle
        type="source"
        position={Position.Right}
        className="!h-3 !w-3 !border-2 !border-white !bg-slate-400"
      />
    </div>
  );
}

export const PipelineCanvasNode = memo(PipelineCanvasNodeInner);
