import { api } from "@/services/api";

export interface UserItem {
  id: string;
  username: string;
  role: string;
  avatar?: string | null;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

export interface UserCreatePayload {
  username: string;
  password: string;
  role?: string;
  avatar?: string | null;
}

export interface UserUpdatePayload {
  username?: string;
  password?: string;
  role?: string;
  avatar?: string | null;
}

export interface ChangePasswordPayload {
  currentPassword: string;
  newPassword: string;
}

export async function getUsersPage(
  current = 1,
  size = 10,
  keyword?: string
): Promise<PageResult<UserItem>> {
  return api.get<PageResult<UserItem>, PageResult<UserItem>>("/users", {
    params: { current, size, keyword: keyword || undefined }
  });
}

export async function createUser(payload: UserCreatePayload): Promise<string> {
  return api.post<string, string>("/users", payload);
}

export async function updateUser(id: string, payload: UserUpdatePayload): Promise<void> {
  await api.put(`/users/${id}`, payload);
}

export async function deleteUser(id: string): Promise<void> {
  await api.delete(`/users/${id}`);
}

export async function changePassword(payload: ChangePasswordPayload): Promise<void> {
  await api.put("/user/password", payload);
}

export interface ProfileUpdatePayload {
  username?: string;
  avatar?: string | null;
}

/**
 * 当前登录用户修改自己的资料（用户名/头像）
 */
export async function updateProfile(payload: ProfileUpdatePayload): Promise<void> {
  await api.put("/user/profile", payload);
}

/**
 * 上传当前用户头像，返回可访问的图片 URL
 */
export async function uploadAvatar(file: File): Promise<string> {
  const formData = new FormData();
  formData.append("file", file);
  // 不手动设置 Content-Type，让 axios 自动携带 multipart boundary
  return api.post<string, string>("/user/avatar/upload", formData);
}
