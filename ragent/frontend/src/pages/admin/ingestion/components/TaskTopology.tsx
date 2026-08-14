import { memo, useEffect, useMemo, useRef } from "react";
import {
  Background,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  type Edge,
  type Node,
  type NodeProps,
  type NodeTypes
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { cn } from "@/lib/utils";
import type {
  IngestionPipelineEdge,
  IngestionPipelineNode,
  IngestionTaskNode
} from "@/services/ingestionService";
import { NODE_TYPE_COLORS, NODE_TYPE_LABELS } from "./PipelineCanvasNode";
import { toFlowEdges, toFlowNodes } from "./pipelineGraphUtils";

export interface TaskTopologyNodeData extends Record<string, unknown> {
  nodeId: string;
  nodeType: string;
  status?: string | null;
  durationMs?: number | null;
}

type TaskNode = Node<TaskTopologyNodeData>;
type TaskEdge = Edge;

const STATUS_DOT: Record<string, string> = {
  success: "bg-emerald-500",
  skipped: "bg-slate-400",
  failed: "bg-red-500"
};

const STATUS_BORDER: Record<string, string> = {
  success: "border-emerald-300",
  skipped: "border-slate-300",
  failed: "border-red-300"
};

function TaskTopologyNodeInner({ data }: NodeProps<TaskNode>) {
  const nodeData = (data ?? {}) as TaskTopologyNodeData;
  const nodeType = nodeData.nodeType || "";
  const label = NODE_TYPE_LABELS[nodeType] || nodeType;
  const color = NODE_TYPE_COLORS[nodeType] || "bg-slate-100 text-slate-700";
  const status = nodeData.status || null;
  const dot = status ? STATUS_DOT[status] || "bg-slate-300" : "bg-slate-300";
  const border = status ? STATUS_BORDER[status] || "border-slate-200" : "border-slate-200";

  return (
    <div
      className={cn(
        "w-44 rounded-lg border bg-white p-3 shadow-sm",
        border
      )}
    >
      <div className="flex items-center gap-2">
        <span className={cn("h-2.5 w-2.5 shrink-0 rounded-full", dot)} />
        <span className={cn("inline-flex items-center rounded px-2 py-0.5 text-xs font-medium", color)}>
          {label}
        </span>
      </div>
      <div className="mt-2 font-mono text-xs text-slate-500">{nodeType}</div>
      <div className="mt-0.5 flex items-center justify-between gap-2">
        <span className="truncate text-xs text-slate-400" title={nodeData.nodeId}>
          {nodeData.nodeId}
        </span>
        {nodeData.durationMs != null && (
          <span className="shrink-0 text-[11px] text-slate-400">{nodeData.durationMs} ms</span>
        )}
      </div>
    </div>
  );
}

const TaskTopologyNode = memo(TaskTopologyNodeInner);

const nodeTypes: NodeTypes = { taskNode: TaskTopologyNode };

interface TaskTopologyProps {
  pipelineNodes: IngestionPipelineNode[] | null | undefined;
  pipelineEdges: IngestionPipelineEdge[] | null | undefined;
  nodeStatuses: Map<string, IngestionTaskNode>;
  taskRunning: boolean;
  selectedNodeId: string | null;
  onNodeClick: (nodeId: string) => void;
  onPaneClick?: () => void;
}

function TaskTopologyInner({
  pipelineNodes,
  pipelineEdges,
  nodeStatuses,
  taskRunning,
  selectedNodeId,
  onNodeClick,
  onPaneClick
}: TaskTopologyProps) {
  const { fitView } = useReactFlow();
  const hasFit = useRef(false);

  const nodes: TaskNode[] = useMemo(() => {
    return toFlowNodes(pipelineNodes).map((node) => {
      const statusNode = nodeStatuses.get(node.id);
      return {
        ...node,
        type: "taskNode",
        selected: node.id === selectedNodeId,
        data: {
          nodeId: node.data.nodeId,
          nodeType: node.data.nodeType,
          status: statusNode?.status ?? null,
          durationMs: statusNode?.durationMs ?? null
        }
      };
    });
  }, [pipelineNodes, nodeStatuses, selectedNodeId]);

  const edges: TaskEdge[] = useMemo(
    () => toFlowEdges(pipelineNodes, pipelineEdges),
    [pipelineNodes, pipelineEdges]
  );

  useEffect(() => {
    if (!hasFit.current && nodes.length > 0) {
      hasFit.current = true;
      requestAnimationFrame(() => fitView({ padding: 0.2, duration: 200 }));
    }
  }, [nodes.length, fitView]);

  const handleNodeClick = (_: React.MouseEvent, node: TaskNode) => {
    onNodeClick(node.id);
  };

  return (
    <div className="h-[480px] w-full rounded-lg border border-slate-200">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        onNodeClick={handleNodeClick}
        onPaneClick={onPaneClick}
        nodesDraggable={false}
        nodesConnectable={false}
        nodesFocusable={false}
        elementsSelectable={true}
        deleteKeyCode={null}
        panOnDrag
        zoomOnScroll
        proOptions={{ hideAttribution: true }}
      >
        <Background gap={16} />
      </ReactFlow>
      {taskRunning && (
        <div className="pointer-events-none relative -top-12 flex justify-center">
          <span className="rounded-full bg-amber-50 px-3 py-1 text-xs text-amber-600">
            任务执行中，节点日志将在完成后生成
          </span>
        </div>
      )}
    </div>
  );
}

export function TaskTopology(props: TaskTopologyProps) {
  return (
    <ReactFlowProvider>
      <TaskTopologyInner {...props} />
    </ReactFlowProvider>
  );
}
