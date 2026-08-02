import * as React from "react";
import { ArrowLeft, Camera, Trash2 } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";

import { Avatar } from "@/components/common/Avatar";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { updateProfile, uploadAvatar } from "@/services/userService";
import { useAuthStore } from "@/stores/authStore";
import { getErrorMessage } from "@/utils/error";
import { resolveAvatar } from "@/utils/helpers";

/** 内置默认头像（与 public/avatars/ 一一对应） */
const DEFAULT_AVATARS = Array.from({ length: 6 }, (_, i) => `/avatars/default-${i + 1}.svg`);

const MAX_AVATAR_SIZE = 5 * 1024 * 1024;
const ALLOWED_TYPES = ["image/jpeg", "image/png", "image/gif", "image/webp"];

export function ProfilePage() {
  const { user, updateCurrentUser } = useAuthStore();
  const navigate = useNavigate();
  const [username, setUsername] = React.useState(user?.username || "");
  const [avatar, setAvatar] = React.useState<string>(user?.avatar || "");
  const [avatarFile, setAvatarFile] = React.useState<File | null>(null);
  const [preview, setPreview] = React.useState<string | null>(null);
  const [saving, setSaving] = React.useState(false);
  const fileInputRef = React.useRef<HTMLInputElement | null>(null);

  // 统一走 resolveAvatar：兼容上传 URL / 默认头像 / 手填相对路径 / 空值回退
  const displayAvatar = preview || resolveAvatar(avatar, user?.userId);
  const isAdmin = user?.role === "admin";

  const handleFileChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;
    if (!ALLOWED_TYPES.includes(file.type)) {
      toast.error("仅支持 jpg/png/gif/webp 格式的图片");
      return;
    }
    if (file.size > MAX_AVATAR_SIZE) {
      toast.error("头像文件不能超过 5MB");
      return;
    }
    if (preview) URL.revokeObjectURL(preview);
    setAvatarFile(file);
    setPreview(URL.createObjectURL(file));
  };

  const handleSave = async () => {
    const trimmedUsername = username.trim();
    if (!trimmedUsername) {
      toast.error("请输入用户名");
      return;
    }
    setSaving(true);
    try {
      // 若选择了本地图片，先上传换取 URL
      let nextAvatar = avatar;
      if (avatarFile) {
        nextAvatar = await uploadAvatar(avatarFile);
      }
      // 默认管理员不允许修改用户名，不提交 username 字段
      await updateProfile({
        username: isAdmin ? undefined : trimmedUsername,
        avatar: nextAvatar
      });
      updateCurrentUser({ username: trimmedUsername, avatar: nextAvatar || undefined });
      if (preview) URL.revokeObjectURL(preview);
      setPreview(null);
      setAvatarFile(null);
      setAvatar(nextAvatar);
      toast.success("资料保存成功");
    } catch (error) {
      toast.error(getErrorMessage(error, "保存失败"));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="relative flex min-h-screen justify-center px-4 py-10">
      <div className="absolute inset-0 bg-gradient-to-br from-slate-50 via-blue-50/50 to-blue-100 dark:from-slate-950 dark:via-slate-900 dark:to-slate-900" />
      <div className="relative z-10 w-full max-w-lg">
        <div className="mb-6 flex items-center gap-3">
          <Button
            variant="ghost"
            size="icon"
            aria-label="返回"
            onClick={() => navigate("/chat")}
          >
            <ArrowLeft className="h-5 w-5" />
          </Button>
          <div>
            <h1 className="font-display text-xl font-semibold">个人资料</h1>
            <p className="text-sm text-muted-foreground">管理你的账号信息与头像</p>
          </div>
        </div>

        <div className="rounded-3xl border border-border/70 bg-background/80 p-6 shadow-soft backdrop-blur">
          {/* 头像设置 */}
          <div className="space-y-4">
            <div className="flex items-start justify-between">
              <div>
                <p className="text-sm font-medium">头像</p>
                <p className="text-xs text-muted-foreground">支持上传图片或选择内置头像</p>
              </div>
              <Avatar
                name={username || user?.username || "用户"}
                src={displayAvatar}
                className="h-16 w-16 text-lg"
              />
            </div>

            <input
              ref={fileInputRef}
              type="file"
              accept="image/jpeg,image/png,image/gif,image/webp"
              className="hidden"
              onChange={handleFileChange}
            />
            <div className="flex flex-wrap items-center gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => fileInputRef.current?.click()}
              >
                <Camera className="mr-1.5 h-4 w-4" />
                上传图片
              </Button>
              {preview || avatar ? (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  className="text-destructive hover:text-destructive"
                  onClick={() => {
                    if (preview) URL.revokeObjectURL(preview);
                    setPreview(null);
                    setAvatarFile(null);
                    setAvatar("");
                  }}
                >
                  <Trash2 className="mr-1.5 h-4 w-4" />
                  移除头像
                </Button>
              ) : null}
            </div>

            <div>
              <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                选择默认头像
              </p>
              <div className="grid grid-cols-6 gap-2">
                {DEFAULT_AVATARS.map((src) => (
                  <button
                    key={src}
                    type="button"
                    aria-label={`默认头像 ${src}`}
                    className={`rounded-full transition-all ${
                      avatar === src && !preview
                        ? "ring-2 ring-blue-500 ring-offset-2"
                        : "hover:opacity-80"
                    }`}
                    onClick={() => {
                      setPreview(null);
                      setAvatarFile(null);
                      setAvatar(src);
                    }}
                  >
                    <img src={src} alt="" className="h-12 w-12 rounded-full object-cover" />
                  </button>
                ))}
              </div>
            </div>
          </div>

          <div className="my-6 h-px bg-border" />

          {/* 用户名设置 */}
          <div className="space-y-2">
            <label className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              用户名
            </label>
            <Input
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              placeholder="请输入用户名"
              disabled={isAdmin}
              className={isAdmin ? "opacity-60" : undefined}
            />
            {isAdmin ? (
              <p className="text-xs text-muted-foreground">默认管理员用户名不可修改</p>
            ) : null}
          </div>

          <div className="mt-6 flex justify-end gap-2">
            <Button variant="outline" onClick={() => navigate("/chat")}>
              取消
            </Button>
            <Button onClick={handleSave} disabled={saving}>
              {saving ? "保存中..." : "保存"}
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}
