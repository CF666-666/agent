# ============================================================
# Ragent 后端镜像（多阶段构建）
# 构建上下文：ragent/（仓库根），docker compose 中 build.context = ./ragent
# ============================================================

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# 先复制 pom 与模块骨架，利用 Maven 依赖缓存
COPY pom.xml .
COPY lombok.config .
COPY framework/ framework/
COPY infra-ai/ infra-ai/
COPY bootstrap/ bootstrap/
COPY mcp-server/ mcp-server/

# 打包可执行 fat-jar（跳过测试与 Spotless 格式检查，容器内无需二次格式化）
RUN mvn -B -q clean package -DskipTests -Dspotless.apply.skip=true -Dspotless.check.skip=true -pl bootstrap -am

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre AS runtime

# 时区与字体（Tesseract OCR 需要基础字体支持）
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-Xms512m -Xmx1024m"

WORKDIR /app

# 健康检查需要 curl
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 可执行 jar
COPY --from=builder /build/bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar /app/app.jar

# 演示数据（FAQ / 图纸 / 图像描述 / 超边）
# docker compose 中通过 bind mount 挂载宿主机 bootstrap/data 覆盖（头像等运行时数据持久化）
COPY bootstrap/data/ /app/data/

EXPOSE 9090

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
