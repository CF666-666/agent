import { GripVertical } from "lucide-react";
import { INGESTION_NODE_TYPES, type IngestionNodeType } from "@/services/ingestionService";
import { NODE_TYPE_LABELS } from "./PipelineCanvasNode";

interface PipelineNodePaletteProps {
  onAdd: (type: IngestionNodeType) => void;
}

export function PipelineNodePalette({ onAdd }: PipelineNodePaletteProps) {
  const handleDragStart = (event: React.DragEvent, type: IngestionNodeType) => {
    event.dataTransfer.setData("application/reactflow", type);
    event.dataTransfer.effectAllowed = "move";
  };

  return (
    <aside className="flex h-full w-56 shrink-0 flex-col border-r border-slate-200 bg-slate-50">
      <div className="border-b border-slate-200 px-3 py-3">
        <p className="text-sm font-semibold text-slate-700">节点面板</p>
        <p className="mt-0.5 text-xs text-slate-400">拖拽或点击添加到画布</p>
      </div>
      <div className="flex-1 space-y-2 overflow-y-auto p-3">
        {INGESTION_NODE_TYPES.map((type) => (
          <div
            key={type}
            draggable
            onDragStart={(event) => handleDragStart(event, type)}
            onClick={() => onAdd(type)}
            className="flex cursor-grab items-center gap-2 rounded-md border border-slate-200 bg-white px-2.5 py-2 text-sm text-slate-700 shadow-sm transition-colors hover:border-indigo-300 hover:bg-indigo-50 active:cursor-grabbing"
            title={`${type}（${NODE_TYPE_LABELS[type] || type}）`}
          >
            <GripVertical className="h-4 w-4 shrink-0 text-slate-300" />
            <div className="min-w-0">
              <div className="font-medium">{NODE_TYPE_LABELS[type] || type}</div>
              <div className="truncate font-mono text-[11px] text-slate-400">{type}</div>
            </div>
          </div>
        ))}
      </div>
    </aside>
  );
}
