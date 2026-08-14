import { useEffect, useState } from "react";
import type { Edge, Node } from "@xyflow/react";
import { X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";
import {
  INGESTION_NODE_TYPES,
  type IngestionCondition
} from "@/services/ingestionService";
import {
  NODE_TYPE_LABELS,
  type PipelineCanvasNodeData
} from "./PipelineCanvasNode";

/** 显式 DAG 边的业务数据（对齐后端 IngestionPipelineEdgeVO） */
export interface PipelineEdgeData extends Record<string, unknown> {
  condition?: IngestionCondition | null;
  priority?: number | null;
  defaultEdge?: boolean;
}

export type FlowNode = Node<PipelineCanvasNodeData>;
export type FlowEdge = Edge<PipelineEdgeData>;

export type SelectedItem =
  | { type: "node"; node: FlowNode }
  | { type: "edge"; edge: FlowEdge };

interface PipelineSidebarProps {
  selected: SelectedItem | null;
  allNodeIds: string[];
  onUpdateNode: (nodeId: string, patch: Partial<PipelineCanvasNodeData>) => void;
  onRenameNode: (oldId: string, newId: string) => void;
  onUpdateEdge: (edgeId: string, patch: Partial<PipelineEdgeData>) => void;
  onClose: () => void;
}

const POSITION_KEY = "__position";

function stripPosition(settings: Record<string, unknown> | null | undefined) {
  if (!settings) return {};
  const rest: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(settings)) {
    if (key !== POSITION_KEY) rest[key] = value;
  }
  return rest;
}

function conditionToText(condition: IngestionCondition | null | undefined): string {
  if (condition == null) return "";
  if (typeof condition === "string") return condition;
  if (typeof condition === "boolean") return String(condition);
  try {
    return JSON.stringify(condition, null, 2);
  } catch {
    return String(condition);
  }
}

function textToCondition(text: string): IngestionCondition {
  const trimmed = text.trim();
  if (!trimmed) return null;
  if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
    try {
      return JSON.parse(trimmed) as IngestionCondition;
    } catch {
      return trimmed;
    }
  }
  return trimmed;
}

function NodeEditor({
  node,
  allNodeIds,
  onUpdateNode,
  onRenameNode,
  onClose
}: {
  node: FlowNode;
  allNodeIds: string[];
  onUpdateNode: (nodeId: string, patch: Partial<PipelineCanvasNodeData>) => void;
  onRenameNode: (oldId: string, newId: string) => void;
  onClose: () => void;
}) {
  const data = node.data;
  const [nodeId, setNodeId] = useState(data.nodeId);
  const [conditionText, setConditionText] = useState(conditionToText(data.condition));
  const [settingsText, setSettingsText] = useState(() =>
    JSON.stringify(stripPosition(data.settings), null, 2)
  );
  const [settingsError, setSettingsError] = useState(false);
  const [maxAttempts, setMaxAttempts] = useState(
    data.executionPolicy?.maxAttempts != null ? String(data.executionPolicy.maxAttempts) : ""
  );
  const [retryBackoffMs, setRetryBackoffMs] = useState(
    data.executionPolicy?.retryBackoffMs != null ? String(data.executionPolicy.retryBackoffMs) : ""
  );

  // 仅当选中节点变化（nodeId 变化）时重置本地草稿，避免编辑 A 字段时被 B 字段提交覆盖
  useEffect(() => {
    setNodeId(data.nodeId);
    setConditionText(conditionToText(data.condition));
    setSettingsText(JSON.stringify(stripPosition(data.settings), null, 2));
    setSettingsError(false);
    setMaxAttempts(data.executionPolicy?.maxAttempts != null ? String(data.executionPolicy.maxAttempts) : "");
    setRetryBackoffMs(
      data.executionPolicy?.retryBackoffMs != null ? String(data.executionPolicy.retryBackoffMs) : ""
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data.nodeId]);

  const nodeIdDuplicated =
    nodeId.trim() !== data.nodeId && allNodeIds.some((id) => id === nodeId.trim());

  const commitNodeId = () => {
    const trimmed = nodeId.trim();
    if (!trimmed || trimmed === data.nodeId) {
      setNodeId(data.nodeId);
      return;
    }
    if (nodeIdDuplicated) return;
    onRenameNode(data.nodeId, trimmed);
  };

  const commitCondition = () => {
    onUpdateNode(data.nodeId, { condition: textToCondition(conditionText) });
  };

  const commitSettings = () => {
    let parsed: Record<string, unknown>;
    try {
      parsed = settingsText.trim() ? JSON.parse(settingsText) : {};
      if (parsed == null || typeof parsed !== "object" || Array.isArray(parsed)) {
        throw new Error("settings 必须是 JSON 对象");
      }
    } catch {
      setSettingsError(true);
      return;
    }
    setSettingsError(false);
    const position = (data.settings ?? {})[POSITION_KEY];
    onUpdateNode(data.nodeId, {
      settings: position != null ? { ...parsed, [POSITION_KEY]: position } : parsed
    });
  };

  const commitExecutionPolicy = () => {
    const maxRaw = maxAttempts.trim() === "" ? null : Number(maxAttempts);
    const backoffRaw = retryBackoffMs.trim() === "" ? null : Number(retryBackoffMs);
    let max = maxRaw != null && !Number.isNaN(maxRaw) ? maxRaw : null;
    let backoff = backoffRaw != null && !Number.isNaN(backoffRaw) ? backoffRaw : null;
    if (max != null) max = Math.min(5, Math.max(1, Math.floor(max)));
    if (backoff != null) backoff = Math.min(60000, Math.max(0, Math.floor(backoff)));
    setMaxAttempts(max != null ? String(max) : "");
    setRetryBackoffMs(backoff != null ? String(backoff) : "");
    onUpdateNode(data.nodeId, {
      executionPolicy: { maxAttempts: max, retryBackoffMs: backoff }
    });
  };

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center justify-between border-b border-slate-200 px-4 py-3">
        <span className="text-sm font-semibold text-slate-700">节点配置</span>
        <Button variant="ghost" size="icon" onClick={onClose} aria-label="关闭侧栏">
          <X className="h-4 w-4" />
        </Button>
      </div>
      <div className="flex-1 space-y-4 overflow-y-auto p-4">
        <div className="space-y-2">
          <Label htmlFor="node-id">节点 ID</Label>
          <Input
            id="node-id"
            value={nodeId}
            onChange={(event) => setNodeId(event.target.value)}
            onBlur={commitNodeId}
            className={cn(nodeIdDuplicated && "border-red-400")}
          />
          {nodeIdDuplicated && (
            <p className="text-xs text-red-500">节点 ID 已存在，请更换</p>
          )}
        </div>

        <div className="space-y-2">
          <Label>节点类型</Label>
          <Select
            value={data.nodeType}
            onValueChange={(value) => onUpdateNode(data.nodeId, { nodeType: value })}
          >
            <SelectTrigger>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {INGESTION_NODE_TYPES.map((type) => (
                <SelectItem key={type} value={type}>
                  {NODE_TYPE_LABELS[type] || type}（{type}）
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="space-y-2">
          <Label htmlFor="node-condition">执行条件（SpEL 或 JSON，可空）</Label>
          <Textarea
            id="node-condition"
            rows={3}
            value={conditionText}
            onChange={(event) => setConditionText(event.target.value)}
            onBlur={commitCondition}
            placeholder='#context.source.type == "file" 或 {"field":"source_type","operator":"eq","value":"file"}'
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="node-settings">
            节点配置 JSON（不含画布位置，自动保留）
          </Label>
          <Textarea
            id="node-settings"
            rows={6}
            value={settingsText}
            onChange={(event) => {
              setSettingsText(event.target.value);
              setSettingsError(false);
            }}
            onBlur={commitSettings}
            className={cn("font-mono text-xs", settingsError && "border-red-400")}
            placeholder='{"strategy":"structure_aware"}'
          />
          {settingsError && (
            <p className="text-xs text-red-500">配置必须是合法 JSON 对象</p>
          )}
        </div>

        <div className="space-y-2">
          <Label>执行策略（可选）</Label>
          <div className="grid grid-cols-2 gap-2">
            <div className="space-y-1">
              <Label htmlFor="max-attempts" className="text-xs text-slate-500">
                maxAttempts
              </Label>
              <Input
                id="max-attempts"
                type="number"
                min={1}
                max={5}
                value={maxAttempts}
                onChange={(event) => setMaxAttempts(event.target.value)}
                onBlur={commitExecutionPolicy}
                placeholder="1"
              />
            </div>
            <div className="space-y-1">
              <Label htmlFor="retry-backoff" className="text-xs text-slate-500">
                retryBackoffMs
              </Label>
              <Input
                id="retry-backoff"
                type="number"
                min={0}
                value={retryBackoffMs}
                onChange={(event) => setRetryBackoffMs(event.target.value)}
                onBlur={commitExecutionPolicy}
                placeholder="0"
              />
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function EdgeEditor({
  edge,
  onUpdateEdge,
  onClose
}: {
  edge: FlowEdge;
  onUpdateEdge: (edgeId: string, patch: Partial<PipelineEdgeData>) => void;
  onClose: () => void;
}) {
  const data = edge.data ?? {};
  const isDefault = data.defaultEdge === true;
  const [conditionText, setConditionText] = useState(conditionToText(data.condition));
  const [priority, setPriority] = useState(data.priority != null ? String(data.priority) : "");
  const [showConditionError, setShowConditionError] = useState(false);

  // 仅当选中边变化时重置本地草稿
  useEffect(() => {
    setConditionText(conditionToText(data.condition));
    setPriority(data.priority != null ? String(data.priority) : "");
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [edge.id]);

  const toggleDefault = (checked: boolean) => {
    if (checked) {
      setConditionText("");
      setShowConditionError(false);
      onUpdateEdge(edge.id, { defaultEdge: true, condition: null });
    } else {
      onUpdateEdge(edge.id, { defaultEdge: false });
    }
  };

  const commitCondition = () => {
    const condition = textToCondition(conditionText);
    if (!isDefault && condition == null) {
      setShowConditionError(true);
      return;
    }
    setShowConditionError(false);
    onUpdateEdge(edge.id, {
      condition,
      defaultEdge: condition == null
    });
  };

  const commitPriority = () => {
    const trimmed = priority.trim();
    const value = trimmed === "" ? null : Number(trimmed);
    onUpdateEdge(edge.id, {
      priority: value != null && !Number.isNaN(value) ? value : null
    });
  };

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center justify-between border-b border-slate-200 px-4 py-3">
        <span className="text-sm font-semibold text-slate-700">连线配置</span>
        <Button variant="ghost" size="icon" onClick={onClose} aria-label="关闭侧栏">
          <X className="h-4 w-4" />
        </Button>
      </div>
      <div className="flex-1 space-y-4 overflow-y-auto p-4">
        <div className="rounded-md bg-slate-50 px-3 py-2 font-mono text-xs text-slate-500">
          {edge.source} → {edge.target}
        </div>

        <label className="flex items-center gap-2 text-sm text-slate-700">
          <input
            type="checkbox"
            checked={isDefault}
            onChange={(event) => toggleDefault(event.target.checked)}
            className="h-4 w-4 rounded border-slate-300"
          />
          默认边（无条件，未命中其他条件时走此边）
        </label>

        <div className="space-y-2">
          <Label htmlFor="edge-condition">条件（SpEL 或 JSON）</Label>
          <Textarea
            id="edge-condition"
            rows={3}
            value={conditionText}
            disabled={isDefault}
            onChange={(event) => setConditionText(event.target.value)}
            onBlur={commitCondition}
            placeholder='#context.source.type == "file"'
            className={cn(showConditionError && "border-red-400")}
          />
          {showConditionError && (
            <p className="text-xs text-red-500">条件边必须填写条件，或改回默认边</p>
          )}
        </div>

        <div className="space-y-2">
          <Label htmlFor="edge-priority">优先级（数值越大越先匹配）</Label>
          <Input
            id="edge-priority"
            type="number"
            value={priority}
            disabled={isDefault}
            onChange={(event) => setPriority(event.target.value)}
            onBlur={commitPriority}
            placeholder="0"
          />
        </div>
      </div>
    </div>
  );
}

export function PipelineSidebar({
  selected,
  allNodeIds,
  onUpdateNode,
  onRenameNode,
  onUpdateEdge,
  onClose
}: PipelineSidebarProps) {
  if (!selected) {
    return (
      <div className="flex h-full w-72 shrink-0 items-center justify-center border-l border-slate-200 bg-white text-sm text-slate-400">
        选中节点或连线以编辑
      </div>
    );
  }

  return (
    <div className="h-full w-72 shrink-0 border-l border-slate-200 bg-white">
      {selected.type === "node" ? (
        <NodeEditor
          node={selected.node}
          allNodeIds={allNodeIds}
          onUpdateNode={onUpdateNode}
          onRenameNode={onRenameNode}
          onClose={onClose}
        />
      ) : (
        <EdgeEditor
          edge={selected.edge}
          onUpdateEdge={onUpdateEdge}
          onClose={onClose}
        />
      )}
    </div>
  );
}
