import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  addEdge,
  Background,
  Controls,
  MiniMap,
  ReactFlow,
  ReactFlowProvider,
  useEdgesState,
  useNodesState,
  useReactFlow,
  type Connection,
  type NodeTypes,
  type XYPosition
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { ArrowLeft, Braces, Save } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import {
  getIngestionPipeline,
  updateIngestionPipeline,
  INGESTION_NODE_TYPES,
  type IngestionNodeType,
  type IngestionPipeline
} from "@/services/ingestionService";
import { PipelineNodePalette } from "./components/PipelineNodePalette";
import { PipelineCanvasNode, type PipelineCanvasNodeData } from "./components/PipelineCanvasNode";
import {
  PipelineSidebar,
  type FlowEdge,
  type FlowNode,
  type PipelineEdgeData,
  type SelectedItem
} from "./components/PipelineSidebar";
import { PipelineJsonDialog, type ParsedGraph } from "./components/PipelineJsonDialog";
import {
  buildPayload,
  collectKnownEdgeIds,
  deriveEdgeView,
  scanMaxNodeIndex,
  serializeGraph,
  toFlowEdges,
  toFlowNodes,
  validateGraph
} from "./components/pipelineGraphUtils";

const nodeTypes: NodeTypes = { pipeline: PipelineCanvasNode };

type SelectedRef = { type: "node"; id: string } | { type: "edge"; id: string } | null;

function PipelineEditorInner() {
  const { pipelineId } = useParams<{ pipelineId: string }>();
  const navigate = useNavigate();
  const [nodes, setNodes, onNodesChange] = useNodesState<FlowNode>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<FlowEdge>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [pipeline, setPipeline] = useState<IngestionPipeline | null>(null);
  const [selectedRef, setSelectedRef] = useState<SelectedRef>(null);
  const [jsonOpen, setJsonOpen] = useState(false);
  const [showErrors, setShowErrors] = useState(true);
  const { screenToFlowPosition, fitView } = useReactFlow();
  const hasFit = useRef(false);
  const nodeIdCounter = useRef(0);
  const edgeIdCounter = useRef(0);
  const knownEdgeIdsRef = useRef<Set<string>>(new Set());

  const loadPipeline = useCallback(async () => {
    if (!pipelineId) return;
    const detail = await getIngestionPipeline(pipelineId);
    setPipeline(detail);
    nodeIdCounter.current = scanMaxNodeIndex(detail.nodes);
    knownEdgeIdsRef.current = collectKnownEdgeIds(detail.edges);
    setNodes(toFlowNodes(detail.nodes));
    setEdges(toFlowEdges(detail.nodes, detail.edges));
  }, [pipelineId, setNodes, setEdges]);

  useEffect(() => {
    let active = true;
    setLoading(true);
    getIngestionPipeline(pipelineId as string)
      .then((detail) => {
        if (!active) return;
        setPipeline(detail);
        nodeIdCounter.current = scanMaxNodeIndex(detail.nodes);
        knownEdgeIdsRef.current = collectKnownEdgeIds(detail.edges);
        setNodes(toFlowNodes(detail.nodes));
        setEdges(toFlowEdges(detail.nodes, detail.edges));
      })
      .catch(() => {
        if (active) {
          toast.error("加载流水线失败");
        }
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [pipelineId, setNodes, setEdges]);

  useEffect(() => {
    if (!loading && !hasFit.current && nodes.length > 0) {
      hasFit.current = true;
      requestAnimationFrame(() => fitView({ padding: 0.2, duration: 200 }));
    }
  }, [loading, nodes.length, fitView]);

  const issues = useMemo(() => validateGraph(nodes, edges), [nodes, edges]);
  const errorCount = issues.length;

  const addNode = useCallback(
    (type: IngestionNodeType, position: XYPosition) => {
      nodeIdCounter.current += 1;
      const nodeId = `node_${nodeIdCounter.current}`;
      const newNode: FlowNode = {
        id: nodeId,
        type: "pipeline",
        position,
        data: { nodeId, nodeType: type }
      };
      setNodes((prev) => prev.concat(newNode));
    },
    [setNodes]
  );

  const handleAddFromPalette = useCallback(
    (type: IngestionNodeType) => {
      addNode(type, { x: 120, y: 120 + nodes.length * 40 });
    },
    [addNode, nodes.length]
  );

  const onDrop = useCallback(
    (event: React.DragEvent) => {
      event.preventDefault();
      const type = event.dataTransfer.getData("application/reactflow") as IngestionNodeType;
      if (!INGESTION_NODE_TYPES.includes(type)) return;
      const position = screenToFlowPosition({ x: event.clientX, y: event.clientY });
      addNode(type, position);
    },
    [screenToFlowPosition, addNode]
  );

  const onDragOver = useCallback((event: React.DragEvent) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = "move";
  }, []);

  const onConnect = useCallback(
    (connection: Connection) => {
      if (!connection.source || !connection.target) return;
      edgeIdCounter.current += 1;
      const newEdge: FlowEdge = deriveEdgeView({
        id: `edge_${edgeIdCounter.current}`,
        source: connection.source,
        target: connection.target,
        data: { condition: null, priority: null, defaultEdge: true }
      });
      setEdges((prev) => addEdge(newEdge, prev));
    },
    [setEdges]
  );

  const onNodesDelete = useCallback(
    (deleted: FlowNode[]) => {
      const ids = new Set(deleted.map((node) => node.id));
      setEdges((prev) =>
        prev.filter((edge) => !ids.has(edge.source) && !ids.has(edge.target))
      );
    },
    [setEdges]
  );

  const onNodeClick = useCallback((_: React.MouseEvent, node: FlowNode) => {
    setSelectedRef({ type: "node", id: node.id });
  }, []);

  const onEdgeClick = useCallback((_: React.MouseEvent, edge: FlowEdge) => {
    setSelectedRef({ type: "edge", id: edge.id });
  }, []);

  const onPaneClick = useCallback(() => {
    setSelectedRef(null);
  }, []);

  const updateNode = useCallback(
    (nodeId: string, patch: Partial<PipelineCanvasNodeData>) => {
      setNodes((prev) =>
        prev.map((node) =>
          node.id === nodeId ? { ...node, data: { ...node.data, ...patch } } : node
        )
      );
    },
    [setNodes]
  );

  const renameNode = useCallback(
    (oldId: string, newId: string) => {
      const trimmed = newId.trim();
      if (!trimmed || trimmed === oldId) return;
      if (nodes.some((node) => node.id === trimmed)) return;
      const match = /^node_(\d+)$/.exec(trimmed);
      if (match) {
        nodeIdCounter.current = Math.max(nodeIdCounter.current, Number(match[1]));
      }
      setNodes((prev) =>
        prev.map((node) =>
          node.id === oldId
            ? { ...node, id: trimmed, data: { ...node.data, nodeId: trimmed } }
            : node
        )
      );
      setEdges((prev) =>
        prev.map((edge) => ({
          ...edge,
          source: edge.source === oldId ? trimmed : edge.source,
          target: edge.target === oldId ? trimmed : edge.target
        }))
      );
      setSelectedRef((prev) =>
        prev?.type === "node" && prev.id === oldId ? { type: "node", id: trimmed } : prev
      );
    },
    [nodes, setNodes, setEdges]
  );

  const updateEdge = useCallback(
    (edgeId: string, patch: Partial<PipelineEdgeData>) => {
      setEdges((prev) =>
        prev.map((edge) =>
          edge.id === edgeId
            ? deriveEdgeView({ ...edge, data: { ...(edge.data ?? {}), ...patch } })
            : edge
        )
      );
    },
    [setEdges]
  );

  const handleSave = useCallback(async () => {
    if (!pipeline) return;
    if (errorCount > 0) {
      setShowErrors(true);
      toast.error("请先修复校验问题");
      return;
    }
    setSaving(true);
    try {
      const payload = buildPayload(pipeline, nodes, edges, knownEdgeIdsRef.current);
      await updateIngestionPipeline(pipeline.id, payload);
      toast.success("保存成功");
      await loadPipeline();
    } catch {
      // 后端错误（环/可达性/正则等）已由 axios 拦截器统一 toast，这里静默
    } finally {
      setSaving(false);
    }
  }, [pipeline, errorCount, nodes, edges, loadPipeline]);

  const jsonInitial = useMemo(
    () => (pipeline ? serializeGraph(pipeline, nodes, edges, knownEdgeIdsRef.current) : "{}"),
    [pipeline, nodes, edges]
  );

  const handleApplyJson = useCallback(
    (data: ParsedGraph) => {
      setPipeline((prev) =>
        prev
          ? { ...prev, name: data.name, description: data.description ?? prev.description }
          : prev
      );
      nodeIdCounter.current = scanMaxNodeIndex(data.nodes);
      knownEdgeIdsRef.current = collectKnownEdgeIds(data.edges);
      setNodes(toFlowNodes(data.nodes));
      setEdges(toFlowEdges(data.nodes, data.edges));
    },
    [setNodes, setEdges]
  );

  const allNodeIds = useMemo(() => nodes.map((node) => node.id), [nodes]);

  const selected: SelectedItem | null = useMemo(() => {
    if (!selectedRef) return null;
    if (selectedRef.type === "node") {
      const node = nodes.find((n) => n.id === selectedRef.id);
      return node ? { type: "node", node } : null;
    }
    const edge = edges.find((e) => e.id === selectedRef.id);
    return edge ? { type: "edge", edge } : null;
  }, [selectedRef, nodes, edges]);

  return (
    <div className="flex h-[calc(100vh-9rem)] flex-col overflow-hidden">
      <div className="flex items-center justify-between border-b border-slate-200 px-4 py-2">
        <div className="flex items-center gap-3">
          <Button variant="ghost" size="sm" onClick={() => navigate("/admin/ingestion?tab=pipelines")}>
            <ArrowLeft className="mr-1 h-4 w-4" />
            返回
          </Button>
          <div>
            <span className="text-sm font-semibold text-slate-800">
              {pipeline?.name || "流水线编辑器"}
            </span>
            <span className="ml-2 text-xs text-slate-400">
              {nodes.length} 节点 · {edges.length} 连线
            </span>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {errorCount > 0 && (
            <Button
              variant="outline"
              size="sm"
              className="border-red-300 text-red-600 hover:bg-red-50"
              onClick={() => setShowErrors((prev) => !prev)}
            >
              校验问题 {errorCount}
            </Button>
          )}
          <Button variant="outline" size="sm" onClick={() => setJsonOpen(true)}>
            <Braces className="mr-1 h-4 w-4" />
            JSON
          </Button>
          <Button size="sm" onClick={handleSave} disabled={saving || loading}>
            <Save className="mr-1 h-4 w-4" />
            {saving ? "保存中..." : "保存"}
          </Button>
        </div>
      </div>

      {showErrors && issues.length > 0 && (
        <div className="border-b border-red-200 bg-red-50 px-4 py-2">
          {issues.map((issue, index) => (
            <p key={index} className="text-xs text-red-600">
              {issue.message}
            </p>
          ))}
        </div>
      )}

      <div className="flex min-h-0 flex-1">
        <PipelineNodePalette onAdd={handleAddFromPalette} />
        <div className="min-w-0 flex-1">
          {loading ? (
            <div className="flex h-full items-center justify-center text-sm text-slate-400">
              加载中...
            </div>
          ) : (
            <ReactFlow
              nodes={nodes}
              edges={edges}
              onNodesChange={onNodesChange}
              onEdgesChange={onEdgesChange}
              onNodesDelete={onNodesDelete}
              onConnect={onConnect}
              onNodeClick={onNodeClick}
              onEdgeClick={onEdgeClick}
              onPaneClick={onPaneClick}
              nodeTypes={nodeTypes}
              onDrop={onDrop}
              onDragOver={onDragOver}
              proOptions={{ hideAttribution: true }}
            >
              <Background gap={16} />
              <Controls />
              <MiniMap pannable zoomable />
            </ReactFlow>
          )}
        </div>
        <PipelineSidebar
          selected={selected}
          allNodeIds={allNodeIds}
          onUpdateNode={updateNode}
          onRenameNode={renameNode}
          onUpdateEdge={updateEdge}
          onClose={() => setSelectedRef(null)}
        />
      </div>

      <PipelineJsonDialog
        open={jsonOpen}
        initialJson={jsonInitial}
        onOpenChange={setJsonOpen}
        onApply={handleApplyJson}
      />
    </div>
  );
}

export default function PipelineEditorPage() {
  return (
    <ReactFlowProvider>
      <PipelineEditorInner />
    </ReactFlowProvider>
  );
}
