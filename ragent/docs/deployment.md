# Ragent 部署指南

> 目标:**刚开机、零依赖**,`docker compose up -d --build` 一条命令拉起整套系统(中间件 + 后端 + 前端),直接向面试官/用户演示。

## 1. 前置条件

| 依赖 | 版本要求 |
|------|---------|
| Docker | ≥ 24(含 Docker Compose v2 插件) |
| 内存 | ≥ 8GB(Milvus + RocketMQ + 后端 Java 进程) |
| 网络 | 构建镜像需访问 Maven 中央仓库与 npm registry |

> 无需在本机安装 Java / Node / PostgreSQL / Milvus —— 全部由镜像提供。

## 2. 一键启动(生产/演示模式)

```bash
# 1. 进入仓库根目录(含 docker-compose.yml)
cd <repo-root>

# 2. 配置 LLM API Key(可选,但问答功能需要)
#    直接在环境变量中设置,或在项目根目录创建 .env:
#     BAILIAN_API_KEY=sk-xxxx
#     SILICONFLOW_API_KEY=sk-xxxx
$env:BAILIAN_API_KEY="sk-xxxx"     # Windows PowerShell
# export BAILIAN_API_KEY="sk-xxxx" # Linux/macOS

# 3. 一键构建并启动全部服务(首次构建耗时 5~15 分钟)
docker compose up -d --build

# 4. 查看启动状态(等待 backend/frontend 健康)
docker compose ps

# 5. 访问
#    前端: http://localhost:5177
#    默认账号: admin / admin
```

启动后前端会自动代理 `/api` 到后端容器,图纸/头像等静态资源由后端 `/files/**` 提供。

## 3. 常用运维命令

```bash
docker compose ps                  # 查看各服务状态
docker compose logs -f backend     # 跟踪后端日志
docker compose logs -f frontend    # 跟踪前端日志
docker compose restart backend     # 重启后端
docker compose down                # 停止并移除容器(保留数据卷)
docker compose down -v             # 停止并删除全部数据卷(重置环境)
docker compose up -d --build backend frontend   # 仅重建并重启应用服务
```

## 4. 数据持久化

| 数据 | 存储 | 说明 |
|------|------|------|
| 业务数据(用户/会话/知识库/超边) | PostgreSQL 卷 `postgres-data` | 首次启动自动执行 schema + 初始化数据 |
| 向量数据 | Milvus 卷 `milvus-data`(+ etcd/rustfs 卷) | 文本/图像集合自动创建 |
| 演示图纸与头像 | 卷 `backend-data` | 首次启动自动从镜像拷贝演示图纸 |
| Redis 缓存 | `redis-data` | 会话/限流/幂等 |

## 5. 服务拓扑与端口

| 服务 | 容器名 | 端口(宿主机) | 说明 |
|------|--------|:--:|------|
| frontend | `ragent-frontend` | 5177 | 前端 Nginx + /api 代理 |
| backend | `ragent-backend` | 9090 | Spring Boot(context-path `/api/ragent`) |
| postgres | `ragent-postgres` | 5432 | 业务库 |
| redis | `ragent-redis` | 6379 | 缓存/限流 |
| rocketmq | `ragent-rmq-*` | 9876/10911 | 消息队列 |
| milvus | `milvus-standalone` | 19530 | 向量库 |
| attu | `milvus-attu` | 8000 | Milvus 可视化管理台 |
| rustfs | `milvus-rustfs` | 9000/9001 | S3 对象存储(Milvus 元数据) |

容器内后端通过服务名连接中间件(`postgres` / `redis` / `rocketmq-namesrv` / `milvus`),无需修改 `application.yaml`。

## 6. 环境变量说明

| 变量 | 必填 | 默认 | 说明 |
|------|:--:|------|------|
| `BAILIAN_API_KEY` | 是(问答) | 空 | 阿里云百炼 API Key(Chat/Rerank) |
| `SILICONFLOW_API_KEY` | 否 | 空 | SiliconFlow API Key(Embedding,缺省回退 Ollama) |
| `OLLAMA_BASE_URL` | 否 | `http://localhost:11434` | 本地 Ollama 地址(容器外时需调整) |

> 容器内如需使用本地 Ollama 模型,需要额外配置 `extra_hosts` 或宿主机地址,生产演示建议直接使用云厂商 API Key。

## 7. 本地开发模式(不适用容器)

仅运行中间件、后端/前端在宿主机直接调试:

```bash
# 中间件容器
docker compose up -d postgres redis rocketmq-namesrv milvus

# 后端(9090)
cd ragent && ./mvnw spring-boot:run -pl bootstrap

# 前端(5173,代理 /api 到 9090)
cd ragent/frontend && npm install && npm run dev
```

## 8. 故障排查

| 现象 | 处理 |
|------|------|
| `backend` 一直 not healthy | `docker compose logs -f backend` 查看启动异常(多为中间件未就绪或 API Key 配置问题) |
| 前端页面能开但接口 404 | 确认 `frontend` 已依赖 `backend` 健康;`docker compose ps` 查看状态 |
| Milvus 相关集合缺失 | 首次启动后自动创建;若失败,`docker compose restart milvus` |
| 端口被占用(9090/5177) | 先停止宿主机同名服务:`Stop-Process`(PowerShell)或 `kill`(Linux) |
| 重置全部数据 | `docker compose down -v && docker compose up -d --build` |
