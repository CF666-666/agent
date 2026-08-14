import { api } from "@/services/api";

export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

/**
 * 8 类摄取节点类型（与后端 IngestionNodeType 枚举一一对应）
 */
export const INGESTION_NODE_TYPES = [
  "fetcher",
  "parser",
  "enhancer",
  "chunker",
  "enricher",
  "indexer",
  "hyperedge_extract",
  "multimodal_parse"
] as const;

export type IngestionNodeType = (typeof INGESTION_NODE_TYPES)[number];

/**
 * 条件表达式（与后端 ConditionEvaluator 支持的形态对齐）
 * - null：无条件，恒真
 * - boolean：直接判定真/假
 * - string：SpEL 表达式
 * - object：结构化条件 { all | any | not | field/operator/value }
 */
export type IngestionCondition =
  | null
  | boolean
  | string
  | {
      all?: IngestionCondition[];
      any?: IngestionCondition[];
      not?: IngestionCondition;
      field?: string;
      operator?: string;
      value?: unknown;
    };

/** 节点级执行策略（对齐后端 NodeExecutionPolicy，缺失表示一次尝试、无退避） */
export interface IngestionNodeExecutionPolicy {
  maxAttempts?: number | null;
  retryBackoffMs?: number | null;
}

/** 显式 DAG 边（对齐后端 IngestionPipelineEdgeVO） */
export interface IngestionPipelineEdge {
  edgeId?: string | null;
  fromNodeId: string;
  toNodeId: string;
  condition?: IngestionCondition | null;
  priority?: number | null;
  defaultEdge?: boolean | null;
}

/** 流水线节点（对齐后端 IngestionPipelineNodeVO） */
export interface IngestionPipelineNode {
  /** 数据库主键（雪花字符串，仅读取时由后端返回） */
  id?: string | null;
  nodeId: string;
  nodeType: string;
  settings?: Record<string, unknown> | null;
  condition?: IngestionCondition | null;
  executionPolicy?: IngestionNodeExecutionPolicy | null;
  /**
   * 线性链 fallback：当该节点没有显式 outgoing edge 时，
   * 后端依据 nextNodeId 合成 defaultEdge=true 的 legacy 边。
   * 画布编辑时应优先使用显式 edges，避免与 nextNodeId 产生双真相。
   */
  nextNodeId?: string | null;
}

/** 提交给后端的节点载荷（对齐 IngestionPipelineNodeRequest，不含数据库主键 id） */
export type IngestionPipelineNodePayload = Omit<IngestionPipelineNode, "id">;

export interface IngestionPipeline {
  id: string;
  name: string;
  description?: string | null;
  createdBy?: string | null;
  nodes?: IngestionPipelineNode[];
  /** 显式 DAG 边（后端语义：edges 优先，nextNodeId 仅作 fallback） */
  edges?: IngestionPipelineEdge[];
  createTime?: string;
  updateTime?: string;
}

export interface IngestionPipelinePayload {
  name: string;
  description?: string | null;
  nodes?: IngestionPipelineNodePayload[];
  /**
   * 显式 DAG 边。
   * 更新语义：null 保留既有边；空数组清空所有边；非空数组整体替换。
   */
  edges?: IngestionPipelineEdge[];
}

export interface IngestionTask {
  id: string;
  pipelineId: string;
  sourceType?: string | null;
  sourceLocation?: string | null;
  sourceFileName?: string | null;
  status?: string | null;
  chunkCount?: number | null;
  errorMessage?: string | null;
  logs?: Array<{
    nodeId: string;
    nodeType: string;
    message?: string | null;
    durationMs?: number | null;
    success?: boolean | null;
    error?: string | null;
  }>;
  metadata?: Record<string, unknown> | null;
  startedAt?: string | null;
  completedAt?: string | null;
  createdBy?: string | null;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface IngestionTaskNode {
  id: string;
  taskId: string;
  pipelineId: string;
  nodeId: string;
  nodeType: string;
  nodeOrder?: number | null;
  status?: string | null;
  durationMs?: number | null;
  message?: string | null;
  errorMessage?: string | null;
  output?: Record<string, unknown> | null;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface IngestionResult {
  taskId: string;
  pipelineId: string;
  status?: string | null;
  chunkCount?: number | null;
  message?: string | null;
}

export interface IngestionTaskCreatePayload {
  pipelineId: string;
  source: {
    type: string;
    location: string;
    fileName?: string | null;
    credentials?: Record<string, string>;
  };
  metadata?: Record<string, unknown>;
  vectorSpaceId?: Record<string, unknown>;
}

export async function getIngestionPipelines(pageNo = 1, pageSize = 10, keyword?: string) {
  return api.get<PageResult<IngestionPipeline>, PageResult<IngestionPipeline>>(
    "/ingestion/pipelines",
    {
      params: { pageNo, pageSize, keyword: keyword || undefined }
    }
  );
}

export async function getIngestionPipeline(id: string) {
  return api.get<IngestionPipeline, IngestionPipeline>(`/ingestion/pipelines/${id}`);
}

export async function createIngestionPipeline(payload: IngestionPipelinePayload) {
  return api.post<IngestionPipeline, IngestionPipeline>("/ingestion/pipelines", payload);
}

export async function updateIngestionPipeline(id: string, payload: IngestionPipelinePayload) {
  return api.put<IngestionPipeline, IngestionPipeline>(`/ingestion/pipelines/${id}`, payload);
}

export async function deleteIngestionPipeline(id: string) {
  await api.delete(`/ingestion/pipelines/${id}`);
}

export async function getIngestionTasks(pageNo = 1, pageSize = 10, status?: string) {
  return api.get<PageResult<IngestionTask>, PageResult<IngestionTask>>("/ingestion/tasks", {
    params: { pageNo, pageSize, status: status || undefined }
  });
}

export async function getIngestionTask(id: string) {
  return api.get<IngestionTask, IngestionTask>(`/ingestion/tasks/${id}`);
}

export async function getIngestionTaskNodes(id: string) {
  return api.get<IngestionTaskNode[], IngestionTaskNode[]>(`/ingestion/tasks/${id}/nodes`);
}

export async function createIngestionTask(payload: IngestionTaskCreatePayload) {
  return api.post<IngestionResult, IngestionResult>("/ingestion/tasks", payload);
}

export async function uploadIngestionTask(pipelineId: string, file: File) {
  const formData = new FormData();
  formData.append("file", file);
  return api.post<IngestionResult, IngestionResult>("/ingestion/tasks/upload", formData, {
    params: { pipelineId },
    headers: { "Content-Type": "multipart/form-data" }
  });
}
