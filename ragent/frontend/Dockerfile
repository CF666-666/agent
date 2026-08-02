# ============================================================
# Ragent 前端镜像（多阶段构建）
# 构建上下文：ragent/frontend/
# ============================================================

# ---------- 构建阶段 ----------
FROM node:20-alpine AS builder
WORKDIR /build

# 先复制依赖清单，利用缓存
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

# 复制源码并构建
COPY . .
ARG VITE_API_BASE_URL=/api/ragent
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

# ---------- 运行阶段 ----------
FROM nginx:alpine AS runtime

# 静态产物 + Nginx 配置（监听 5177，代理 /api 到后端容器）
COPY --from=builder /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 5177

HEALTHCHECK --interval=15s --timeout=5s --retries=5 CMD wget -qO- http://127.0.0.1:5177/ >/dev/null 2>&1 || exit 1
