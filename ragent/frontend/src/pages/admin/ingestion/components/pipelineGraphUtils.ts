import type { XYPosition } from "@xyflow/react";
import type {
  IngestionCondition,
  IngestionPipeline,
  IngestionPipelineEdge,
  IngestionPipelineNode,
  IngestionPipelinePayload
} from "@/services/ingestionService";
import type { FlowEdge, FlowNode, PipelineEdgeData } from "./PipelineSidebar";

const POSITION_KEY = "__position";

const DEFAULT_EDGE_STYLE = { stroke: "#94a3b8", strokeWidth: 1.5 };
const CONDITIONAL_EDGE_STYLE = { stroke: "#6366f1", strokeWidth: 1.5, strokeDasharray: "5 5" };

/** 读取后端 settings 里持久化的画布位置，非法时返回 null */
export function readPosition(
  settings: Record<string, unknown> | null | undefined
): XYPosition | null {
  const raw = settings?.[POSITION_KEY] as { x?: number; y?: number } | undefined;
  if (raw && typeof raw.x === "number" && typeof raw.y === "number") {
    return { x: raw.x, y: raw.y };
  }
  return null;
}

/** 扫描后端节点中 node_N 格式的最大序号 */
export function scanMaxNodeIndex(nodes: IngestionPipelineNode[] | null | undefined): number {
  let max = 0;
  if (nodes?.length) {
    for (const node of nodes) {
      const match = /^node_(\d+)$/.exec(node.nodeId);
      if (match) {
        max = Math.max(max, Number(match[1]));
      }
    }
  }
  return max;
}

export function toFlowNodes(nodes: IngestionPipelineNode[] | null | undefined): FlowNode[] {
  if (!nodes?.length) return [];
  return nodes.map((node, index) => {
    const settings = (node.settings ?? {}) as Record<string, unknown>;
    const position = readPosition(settings);
    return {
      id: node.nodeId,
      type: "pipeline",
      position: position ?? { x: index * 220, y: (index % 2) * 140 },
      data: {
        nodeId: node.nodeId,
        nodeType: node.nodeType,
        settings: node.settings,
        condition: node.condition,
        executionPolicy: node.executionPolicy
      }
    };
  });
}

function edgeLabelFromCondition(condition: IngestionCondition | null | undefined): string {
  if (condition == null) return "";
  if (typeof condition === "string") return condition;
  if (typeof condition === "boolean") return String(condition);
  try {
    const text = JSON.stringify(condition);
    return text.length > 24 ? `${text.slice(0, 24)}…` : text;
  } catch {
    return "";
  }
}

/** 根据边的业务数据派生 React Flow 的 label 与样式 */
export function deriveEdgeView(edge: FlowEdge): FlowEdge {
  const data = edge.data ?? {};
  const isDefault = data.defaultEdge === true;
  return {
    ...edge,
    label: isDefault ? "默认" : edgeLabelFromCondition(data.condition),
    labelStyle: { fill: "#64748b", fontSize: 11 },
    labelBgStyle: { fill: "#ffffff", fillOpacity: 0.9 },
    style: isDefault ? DEFAULT_EDGE_STYLE : CONDITIONAL_EDGE_STYLE
  };
}

/**
 * 映射显式边，并对仅有 nextNodeId（legacy 数据）的节点合成默认边，
 * 与后端 PipelineGraph.buildEffectiveEdges 的 fallback 语义保持一致。
 */
export function toFlowEdges(
  nodes: IngestionPipelineNode[] | null | undefined,
  edges: IngestionPipelineEdge[] | null | undefined
): FlowEdge[] {
  const result: FlowEdge[] = [];
  const explicitSources = new Set<string>();

  if (edges?.length) {
    edges.forEach((edge, index) => {
      explicitSources.add(edge.fromNodeId);
      const flowEdge: FlowEdge = {
        id: edge.edgeId || `${edge.fromNodeId}->${edge.toNodeId}:${index}`,
        source: edge.fromNodeId,
        target: edge.toNodeId,
        data: {
          condition: edge.condition,
          priority: edge.priority,
          defaultEdge: edge.defaultEdge === true
        }
      };
      result.push(deriveEdgeView(flowEdge));
    });
  }

  if (nodes?.length) {
    let legacyIndex = 0;
    for (const node of nodes) {
      if (node.nextNodeId && !explicitSources.has(node.nodeId)) {
        const flowEdge: FlowEdge = {
          id: `legacy:${node.nodeId}->${node.nextNodeId}:${legacyIndex++}`,
          source: node.nodeId,
          target: node.nextNodeId,
          data: { condition: null, priority: null, defaultEdge: true }
        };
        result.push(deriveEdgeView(flowEdge));
      }
    }
  }

  return result;
}

/** 收集后端返回的真实 edgeId（用于保存时区分临时边与真实边） */
export function collectKnownEdgeIds(
  edges: IngestionPipelineEdge[] | null | undefined
): Set<string> {
  const set = new Set<string>();
  if (edges?.length) {
    for (const edge of edges) {
      if (edge.edgeId) set.add(edge.edgeId);
    }
  }
  return set;
}

/** DFS 三色标记检测环，返回环路径或 null */
export function detectCycle(nodes: FlowNode[], edges: FlowEdge[]): string[] | null {
  const adj = new Map<string, string[]>();
  for (const edge of edges) {
    const list = adj.get(edge.source) ?? [];
    list.push(edge.target);
    adj.set(edge.source, list);
  }
  const state = new Map<string, number>(); // 0=new, 1=visiting, 2=done
  const dfs = (id: string, path: string[]): string[] | null => {
    state.set(id, 1);
    for (const next of adj.get(id) ?? []) {
      const s = state.get(next) ?? 0;
      if (s === 1) {
        const start = path.indexOf(next);
        return [...path.slice(start >= 0 ? start : 0), next];
      }
      if (s === 0) {
        const cycle = dfs(next, [...path, next]);
        if (cycle) return cycle;
      }
    }
    state.set(id, 2);
    return null;
  };
  for (const node of nodes) {
    if ((state.get(node.id) ?? 0) === 0) {
      const cycle = dfs(node.id, [node.id]);
      if (cycle) return cycle;
    }
  }
  return null;
}

export interface ValidationIssue {
  severity: "error";
  message: string;
}

/** 前端即时校验，覆盖后端 PipelineGraph 的校验项 */
export function validateGraph(nodes: FlowNode[], edges: FlowEdge[]): ValidationIssue[] {
  const issues: ValidationIssue[] = [];
  const nodeIds = new Set(nodes.map((node) => node.id));

  if (nodes.length === 0) {
    issues.push({ severity: "error", message: "流水线至少需要一个节点" });
    return issues;
  }

  const seenNodeIds = new Map<string, number>();
  for (const node of nodes) {
    const trimmed = node.data.nodeId?.trim();
    if (!trimmed) {
      issues.push({ severity: "error", message: "存在未命名的节点（nodeId 为空）" });
      break;
    }
    seenNodeIds.set(trimmed, (seenNodeIds.get(trimmed) ?? 0) + 1);
  }
  for (const [id, count] of seenNodeIds) {
    if (count > 1) {
      issues.push({ severity: "error", message: `节点 ID ${id} 重复` });
    }
  }

  for (const edge of edges) {
    if (!nodeIds.has(edge.source)) {
      issues.push({ severity: "error", message: `边 ${edge.source}→${edge.target} 的源节点不存在` });
    }
    if (!nodeIds.has(edge.target)) {
      issues.push({ severity: "error", message: `边 ${edge.source}→${edge.target} 的目标节点不存在` });
    }
    const isDefault = edge.data?.defaultEdge === true;
    const hasCondition = edge.data?.condition != null;
    if (isDefault && hasCondition) {
      issues.push({ severity: "error", message: `默认边 ${edge.source}→${edge.target} 不能设置条件` });
    }
    if (!isDefault && !hasCondition) {
      issues.push({ severity: "error", message: `条件边 ${edge.source}→${edge.target} 未设置条件` });
    }
  }

  const defaultCounts = new Map<string, number>();
  for (const edge of edges) {
    if (edge.data?.defaultEdge === true) {
      defaultCounts.set(edge.source, (defaultCounts.get(edge.source) ?? 0) + 1);
    }
  }
  for (const [source, count] of defaultCounts) {
    if (count > 1) {
      issues.push({ severity: "error", message: `节点 ${source} 有 ${count} 条默认出口（最多 1 条）` });
    }
  }

  const priorityMap = new Map<string, Map<number, number>>();
  for (const edge of edges) {
    if (edge.data?.defaultEdge === true) continue;
    const priority = edge.data?.priority;
    if (priority == null) continue;
    const byPriority = priorityMap.get(edge.source) ?? new Map<number, number>();
    byPriority.set(priority, (byPriority.get(priority) ?? 0) + 1);
    priorityMap.set(edge.source, byPriority);
  }
  for (const [source, byPriority] of priorityMap) {
    for (const [priority, count] of byPriority) {
      if (count > 1) {
        issues.push({ severity: "error", message: `节点 ${source} 有 ${count} 条条件边优先级同为 ${priority}` });
      }
    }
  }

  const cycle = detectCycle(nodes, edges);
  if (cycle) {
    issues.push({ severity: "error", message: `存在环路：${cycle.join(" → ")}` });
    return issues;
  }

  const indegree = new Map<string, number>();
  nodes.forEach((node) => indegree.set(node.id, 0));
  for (const edge of edges) {
    indegree.set(edge.target, (indegree.get(edge.target) ?? 0) + 1);
  }
  const starts = nodes.filter((node) => indegree.get(node.id) === 0).map((node) => node.id);
  if (starts.length === 0) {
    issues.push({ severity: "error", message: "未找到起始节点（所有节点都有入边）" });
  } else if (starts.length > 1) {
    issues.push({ severity: "error", message: `存在多个起始节点：${starts.join(", ")}` });
  } else {
    const visited = new Set<string>();
    const queue = [starts[0]];
    while (queue.length) {
      const id = queue.shift() as string;
      if (visited.has(id)) continue;
      visited.add(id);
      for (const edge of edges) {
        if (edge.source === id) queue.push(edge.target);
      }
    }
    const unreachable = nodes.filter((node) => !visited.has(node.id)).map((node) => node.id);
    if (unreachable.length) {
      issues.push({ severity: "error", message: `存在不可达节点：${unreachable.join(", ")}` });
    }
  }

  return issues;
}

/** 为未显式设置 priority 的条件边自动分配不冲突的优先级 */
function assignPriorities(edges: FlowEdge[]): Map<string, number> {
  const explicitBySource = new Map<string, number[]>();
  for (const edge of edges) {
    if (edge.data?.defaultEdge === true) continue;
    const priority = edge.data?.priority;
    if (priority != null) {
      const list = explicitBySource.get(edge.source) ?? [];
      list.push(priority);
      explicitBySource.set(edge.source, list);
    }
  }
  const nextPriority = new Map<string, number>();
  for (const [source, list] of explicitBySource) {
    nextPriority.set(source, Math.max(...list) + 10);
  }
  const assigned = new Map<string, number>();
  for (const edge of edges) {
    if (edge.data?.defaultEdge === true) continue;
    if (edge.data?.priority != null) continue;
    const base = nextPriority.get(edge.source) ?? 10;
    assigned.set(edge.id, base);
    nextPriority.set(edge.source, base + 10);
  }
  return assigned;
}

/** 组装保存 payload：position 写回 settings，legacy 边转显式边，nextNodeId 清空 */
export function buildPayload(
  pipeline: IngestionPipeline,
  nodes: FlowNode[],
  edges: FlowEdge[],
  knownEdgeIds: Set<string>
): IngestionPipelinePayload {
  const assignedPriorities = assignPriorities(edges);

  const nodePayloads: IngestionPipelinePayload["nodes"] = nodes.map((node) => {
    const settings = (node.data.settings ?? {}) as Record<string, unknown>;
    return {
      nodeId: node.data.nodeId,
      nodeType: node.data.nodeType,
      settings: {
        ...settings,
        [POSITION_KEY]: { x: Math.round(node.position.x), y: Math.round(node.position.y) }
      },
      condition: node.data.condition ?? null,
      executionPolicy: node.data.executionPolicy ?? null,
      nextNodeId: null
    };
  });

  const edgePayloads: IngestionPipelinePayload["edges"] = edges.map((edge) => {
    const data = (edge.data ?? {}) as PipelineEdgeData;
    const isDefault = data.defaultEdge === true;
    const priority = isDefault ? 0 : data.priority ?? assignedPriorities.get(edge.id) ?? null;
    return {
      edgeId: knownEdgeIds.has(edge.id) ? edge.id : null,
      fromNodeId: edge.source,
      toNodeId: edge.target,
      condition: data.condition ?? null,
      priority,
      defaultEdge: isDefault
    };
  });

  return {
    name: pipeline.name,
    description: pipeline.description,
    nodes: nodePayloads,
    edges: edgePayloads
  };
}

/** 画布状态序列化为 JSON（用于 JSON 高级编辑模式，与 buildPayload 同构） */
export function serializeGraph(
  pipeline: IngestionPipeline,
  nodes: FlowNode[],
  edges: FlowEdge[],
  knownEdgeIds: Set<string>
): string {
  return JSON.stringify(buildPayload(pipeline, nodes, edges, knownEdgeIds), null, 2);
}
