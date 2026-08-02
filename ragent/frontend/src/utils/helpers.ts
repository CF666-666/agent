import { format } from "date-fns";

export function formatTimestamp(value?: string) {
  if (!value) return "";
  try {
    return format(new Date(value), "MM月dd日 HH:mm");
  } catch {
    return "";
  }
}

export function truncate(text: string, max = 36) {
  if (!text) return "";
  if (text.length <= max) return text;
  return `${text.slice(0, max)}...`;
}

export function buildQuery(params: Record<string, string | number | boolean | undefined | null>) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === "") return;
    search.set(key, String(value));
  });
  const query = search.toString();
  return query ? `?${query}` : "";
}

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");

/**
 * 解析静态资源 URL：相对路径（/files/...）拼接 API Base，绝对 URL 原样返回
 */
export function resolveAssetUrl(url?: string | null): string {
  if (!url) return "";
  if (/^https?:\/\//i.test(url)) return url;
  if (url.startsWith("/")) return `${API_BASE_URL}${url}`;
  return url;
}

/** 内置默认头像数量 */
const DEFAULT_AVATAR_COUNT = 6;

function hashString(input: string): number {
  let hash = 0;
  for (let i = 0; i < input.length; i++) {
    hash = (hash << 5) - hash + input.charCodeAt(i);
    hash |= 0;
  }
  return Math.abs(hash);
}

/**
 * 解析用户头像地址：
 * - 有自定义头像则原样返回（绝对 URL / 上传的 /api/ragent/files/... 均直接可用）
 * - 兼容管理端手填的相对路径（/files/...）统一拼接 API Base
 * - 无头像时按 seed（如 userId）稳定分配一个内置默认头像
 */
export function resolveAvatar(avatar?: string | null, seed?: string): string {
  const value = avatar?.trim();
  if (value) {
    if (value.startsWith("/files/")) {
      return resolveAssetUrl(value);
    }
    return value;
  }
  const index = (hashString(seed || "") % DEFAULT_AVATAR_COUNT) + 1;
  return `/avatars/default-${index}.svg`;
}
