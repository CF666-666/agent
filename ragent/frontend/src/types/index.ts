export type Role = "user" | "assistant";

export type FeedbackValue = "like" | "dislike" | null;

/**
 * 引用来源类型（与后端 ReferenceType 枚举一一对应）
 */
export type ReferenceType = "TEXT" | "IMAGE" | "HYPERGRAPH";

/**
 * 引用来源（与后端 rag.dto.Reference 字段一一对应）
 */
export interface Reference {
  type: ReferenceType;
  label: string;
  url?: string | null;
  detail?: string | null;
  snippet?: string | null;
  /** 附加信息（如匹配分数 score） */
  extra?: Record<string, unknown> | null;
}

export type MessageStatus = "streaming" | "done" | "cancelled" | "error";

export interface User {
  userId: string;
  username?: string;
  role: string;
  token: string;
  avatar?: string;
}

export type CurrentUser = Omit<User, "token">;

export interface Session {
  id: string;
  title: string;
  lastTime?: string;
}

export interface Message {
  id: string;
  role: Role;
  content: string;
  thinking?: string;
  thinkingDuration?: number;
  isDeepThinking?: boolean;
  isThinking?: boolean;
  createdAt?: string;
  feedback?: FeedbackValue;
  status?: MessageStatus;
  /** 引用来源列表（assistant 消息在生成完成后合并） */
  references?: Reference[];
}

export interface StreamMetaPayload {
  conversationId: string;
  taskId: string;
}

export interface MessageDeltaPayload {
  type: string;
  delta: string;
}

export interface CompletionPayload {
  messageId?: string | null;
  title?: string | null;
}
