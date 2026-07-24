# Ragent 快速启动指南

> 从零克隆项目 → 启动全部基础设施 → 编译运行后端 → 打开前端界面

---

## 环境要求

| 软件 | 版本要求 | 说明 |
|------|---------|------|
| **Docker & Docker Compose** | 最新版 | 运行基础设施容器 |
| **Java JDK** | 17+ | 编译运行 Spring Boot 后端 |
| **Maven** | 3.8+ | 项目构建 |
| **Node.js**（仅开发前端时） | 18+ | 前端开发/构建 |

---

## 一、克隆项目

```bash
git clone https://github.com/CF666-666/agent.git
cd agent
```

---

## 二、启动基础设施（Docker Compose）

一键启动全部依赖服务：

```bash
docker compose up -d
```

启动后包含以下服务：

| 服务 | 端口 | 用途 |
|------|------|------|
| **PostgreSQL** | 5432 | 业务数据库（自动初始化 schema + 种子数据） |
| **Redis** | 6379 | 缓存 / 分布式限流 / 会话管理 |
| **RocketMQ** | 9876 (NameSrv) / 10911 (Broker) | 消息队列 |
| **Milvus** | 19530 | 向量数据库 |
| **RustFS** | 9000 (API) / 9001 (Console) | S3 兼容对象存储 |
| **Attu** | 8000 | Milvus 可视化管理界面 |
| **Nginx** | 5177 | 前端静态服务 + API 反向代理 |

验证服务状态：

```bash
docker compose ps
```

等待所有服务 `healthy` 后继续。

**关于 RocketMQ：** 首次启动时 broker 会自动创建 `localhost` 集群的默认 Topic，约需 30-60 秒。如果后端日志报 RocketMQ 连接失败，稍等重试即可。

---

## 三、配置 API Key（必须）

后端需要百炼 / SiliconFlow 的 API Key 才能调用大模型。

**Windows (PowerShell):**
```powershell
$env:BAILIAN_API_KEY = "你的百炼APIKey"
$env:SILICONFLOW_API_KEY = "你的硅基流动APIKey"
```

**macOS / Linux:**
```bash
export BAILIAN_API_KEY="你的百炼APIKey"
export SILICONFLOW_API_KEY="你的硅基流动APIKey"
```

> 至少需要其中一个才能运行 Embedding 和 Chat。推荐先用 SiliconFlow 免费额度。
> 
> - SiliconFlow 注册：https://siliconflow.cn → 控制台 → API 密钥
> - 百炼注册：https://bailian.console.aliyun.com → API-KEY 管理

---

## 四、编译启动后端

```bash
cd ragent
./mvnw clean compile -DskipTests -pl bootstrap -am
./mvnw spring-boot:run -pl bootstrap
```

后端启动在 `http://localhost:9090/api/ragent`

---

## 五、打开前端

浏览器访问：

```
http://localhost:5177
```

- 问答页面即为首页
- 管理后台：需先注册用户后在数据库中设为管理员

---

## 六、前端开发（可选）

如需修改前端代码并构建：

```bash
cd ragent/frontend
npm install
npm run dev          # 开发模式（Vite 热更新，端口 5173）
npm run build        # 构建产物 → dist/
```

构建后将 `dist/` 复制到 `nageoffer-nginx-2.0.1/html/dist-ragent/`：

```bash
# Windows
xcopy /E /Y dist\* ..\..\nageoffer-nginx-2.0.1\html\dist-ragent\

# macOS / Linux
cp -r dist/* ../../nageoffer-nginx-2.0.1/html/dist-ragent/
```

重启 nginx 容器生效：

```bash
docker compose restart nginx
```

---

## 七、轻量模式（低配机器）

如果内存不足（< 8GB），使用轻量版 Milvus 配置：

```bash
docker compose -f ragent/resources/docker/lightweight/milvus-stack-2.6.6.compose.yaml up -d
```

这会给每个容器设置内存上限，总占用约 2.5GB。

---

## 八、常见问题

### Q: Milvus 启动失败（CentOS 7）

CentOS 7 内核过旧不支持 Milvus 2.6.x，使用降级版：

```bash
docker compose -f ragent/resources/docker/lightweight/milvus-stack-2.5.8.compose.yaml up -d
```

### Q: 前端页面空白或 API 请求 502

检查 Spring Boot 后端是否启动成功（`http://localhost:9090`）。

### Q: Embedding 调用失败

确认 SiliconFlow API Key 已设置且有额度。免费注册送额度。

### Q: 端口被占用

修改根目录 `docker-compose.yml` 中的端口映射，同步修改 `ragent/bootstrap/src/main/resources/application.yaml` 中对应的连接地址。

---

> **提示**：首次启动会拉取多个 Docker 镜像（总计约 3-5GB），建议在良好网络环境下执行。
