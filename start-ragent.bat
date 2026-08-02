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

rem ========== 2. 检查 .env 是否存在 ==========
if not exist ".env" (
    echo.
    echo [!] 未找到 .env 环境变量文件
    echo     正在从 .env.example 复制模板并打开编辑...
    copy /y ".env.example" ".env" >nul
    start notepad ".env"
    echo.
    echo     请填写 BAILIAN_API_KEY 后保存，再重新双击本脚本启动。
    echo.
    pause
    exit /b 1
)

rem ========== 3. 检查 API Key 是否填写 ==========
findstr /b "BAILIAN_API_KEY=." ".env" >nul 2>&1
if errorlevel 1 (
    echo.
    echo [!] .env 中的 BAILIAN_API_KEY 尚未填写
    echo     正在打开 .env，请填写后保存，再重新双击本脚本启动。
    echo.
    start notepad ".env"
    pause
    exit /b 1
)

echo [1/3] 检查应用镜像...
docker image inspect ragent-backend >nul 2>&1
if errorlevel 1 (
    echo [2/3] 首次构建镜像（约 5-15 分钟，请耐心等待）...
    docker compose build
    if errorlevel 1 (
        echo.
        echo [错误] 镜像构建失败，请查看上方日志。
        pause
        exit /b 1
    )
) else (
    echo [2/3] 镜像已存在，跳过构建...
)

echo [3/3] 启动全部服务（中间件 + 后端 + 前端）...
docker compose up -d
if errorlevel 1 (
    echo.
    echo [错误] 服务启动失败，请查看上方日志。
    pause
    exit /b 1
)

echo.
echo     等待后端服务就绪...
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
