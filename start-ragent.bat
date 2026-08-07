@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
title Ragent 一键启动

cd /d "%~dp0"

echo ============================================
echo    Ragent 智研中枢系统 - 一键启动
echo ============================================
echo.

rem ========== 1. 检查 Docker 是否运行 ==========
docker info >nul 2>&1
if errorlevel 1 (
    echo [!] Docker 未运行，正在启动 Docker Desktop...
    if exist "C:\Program Files\Docker\Docker\Docker Desktop.exe" (
        start "" "C:\Program Files\Docker\Docker\Docker Desktop.exe"
    ) else (
        echo     未找到 Docker Desktop，请手动启动后重新运行本脚本。
        echo.
        pause
        exit /b 1
    )
    echo     等待 Docker 就绪（首次启动可能需要 1-2 分钟）...
    :wait_docker
    timeout /t 5 /nobreak >nul
    docker info >nul 2>&1
    if errorlevel 1 goto wait_docker
    echo [OK] Docker 已就绪
)

rem ========== 2. 优先使用 Windows 用户环境变量，其次读取 .env ==========
set "has_bailian_key="
set "has_siliconflow_key="
if defined BAILIAN_API_KEY set "has_bailian_key=1"
if defined SILICONFLOW_API_KEY set "has_siliconflow_key=1"

if not defined has_bailian_key if exist ".env" (
    findstr /b "BAILIAN_API_KEY=." ".env" >nul 2>&1 && set "has_bailian_key=1"
)
if not defined has_siliconflow_key if exist ".env" (
    findstr /b "SILICONFLOW_API_KEY=." ".env" >nul 2>&1 && set "has_siliconflow_key=1"
)

if not defined has_bailian_key (
    echo.
    echo [!] 未检测到 BAILIAN_API_KEY。
    echo     请在 Windows 用户变量中设置，或在 .env 中填写后重试。
    if not exist ".env" if exist ".env.example" copy /y ".env.example" ".env" >nul
    if exist ".env" start notepad ".env"
    pause
    exit /b 1
)

if defined BAILIAN_API_KEY (
    echo [OK] 已读取 Windows 用户变量 BAILIAN_API_KEY。
) else (
    echo [OK] 已读取 .env 中的 BAILIAN_API_KEY。
)
if defined has_siliconflow_key (
    echo [OK] 已检测到 SILICONFLOW_API_KEY。
) else (
    echo [!] 未检测到 SILICONFLOW_API_KEY，将按应用配置尝试本地 Embedding 降级。
)

echo [1/2] 构建并启动全部服务（首次约需 5-15 分钟）...
docker compose up -d --build
if errorlevel 1 (
    echo.
    echo [错误] 服务构建或启动失败，请查看上方日志。
    pause
    exit /b 1
)

echo [2/2] 等待后端服务就绪...
echo.
:wait_health
timeout /t 5 /nobreak >nul
set health=starting
for /f "usebackq tokens=*" %%s in (`docker inspect -f "{{.State.Health.Status}}" ragent-backend 2^>nul`) do set health=%%s
if not "!health!"=="healthy" goto wait_health

echo.
echo ============================================
echo    启动完成！
echo.
echo    前端地址: http://localhost:5177
echo    默认账号: admin / admin
echo ============================================
echo.
start "" "http://localhost:5177"
echo 服务已在后台运行，按任意键关闭本窗口。
pause >nul
endlocal
