@echo off
setlocal EnableExtensions EnableDelayedExpansion
title Ragent Startup

cd /d "%~dp0"

rem ---------- Load cloud-model keys without echoing them ----------
rem Explorer may not inherit variables added after sign-in, so read Machine
rem first, then User. Docker Compose also reads .env as its final fallback.
if "%SILICONFLOW_API_KEY%"=="" (
    for /f "usebackq delims=" %%k in (`powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('SILICONFLOW_API_KEY', 'Machine')"`) do set "SILICONFLOW_API_KEY=%%k"
)
if "%SILICONFLOW_API_KEY%"=="" (
    for /f "usebackq delims=" %%k in (`powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('SILICONFLOW_API_KEY', 'User')"`) do set "SILICONFLOW_API_KEY=%%k"
)
if "%SILICONFLOW_API_KEY%"=="" (
    echo [WARN] SILICONFLOW_API_KEY is not available. Chat features will use configured fallbacks.
)
rem BaiLian is optional. It is used only after enabling a BaiLian candidate in application.yaml.
if "%BAILIAN_API_KEY%"=="" (
    for /f "usebackq delims=" %%k in (`powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('BAILIAN_API_KEY', 'Machine')"`) do set "BAILIAN_API_KEY=%%k"
)
if "%BAILIAN_API_KEY%"=="" (
    for /f "usebackq delims=" %%k in (`powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('BAILIAN_API_KEY', 'User')"`) do set "BAILIAN_API_KEY=%%k"
)

echo ============================================
echo Ragent one-click startup
echo ============================================
echo.

rem ---------- Quick start mode ----------
rem If FASTSTART=1 (or --fast / /fast flag), skip build even if images missing,
rem falling back to compose pull + up. Use this when build is too slow.
set "QUICK_MODE=0"
if /i "%~1"=="--fast" set "QUICK_MODE=1"
if /i "%~1"=="/fast" set "QUICK_MODE=1"
if /i "%FASTSTART%"=="1" set "QUICK_MODE=1"

rem ---------- 1. Check Docker CLI ----------
where docker >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker CLI was not found. Install Docker Desktop first.
    pause
    exit /b 1
)

rem ---------- 2. Make sure Docker daemon is up ----------
docker info >nul 2>&1
if errorlevel 1 (
    echo Docker is not ready. Starting Docker Desktop...
    if exist "%ProgramFiles%\Docker\Docker\Docker Desktop.exe" (
        start "" "%ProgramFiles%\Docker\Docker\Docker Desktop.exe"
    ) else if exist "C:\Program Files\Docker\Docker\Docker Desktop.exe" (
        start "" "C:\Program Files\Docker\Docker\Docker Desktop.exe"
    ) else (
        echo [ERROR] Docker Desktop was not found.
        pause
        exit /b 1
    )

    echo Waiting for Docker Desktop...
    set /a docker_wait=0
:wait_docker
    timeout /t 5 /nobreak >nul
    docker info >nul 2>&1
    if not errorlevel 1 goto docker_ready
    set /a docker_wait+=1
    if !docker_wait! GEQ 36 (
        echo [ERROR] Docker Desktop did not become ready within 3 minutes.
        pause
        exit /b 1
    )
    goto wait_docker
)

:docker_ready
echo [OK] Docker is ready.

rem ---------- 3. Decide whether to build ----------
rem Use docker image inspect (rock-solid) to check image presence.
set "FE_OK=0"
set "BE_OK=0"
if %QUICK_MODE%==1 (
    echo [FAST] Quick mode: skipping image build entirely.
    goto after_build
)
docker image inspect ragent-stack-frontend:latest >nul 2>&1
if not errorlevel 1 set "FE_OK=1"
docker image inspect ragent-stack-backend:latest >nul 2>&1
if not errorlevel 1 set "BE_OK=1"

if !FE_OK!==1 if !BE_OK!==1 (
    echo [OK] Both ragent-stack-frontend and ragent-stack-backend images already exist locally.
    echo       Skipping the slow build step. If you need to rebuild after code changes,
    echo       run: docker compose build
    goto after_build
)

echo One or more images missing, building... (only first time should be slow)
docker compose build
if errorlevel 1 (
    echo [ERROR] Image build failed.
    pause
    exit /b 1
)

:after_build

rem ---------- 4. Start all services ----------
echo Starting all services...
docker compose up -d
if errorlevel 1 (
    echo [ERROR] Docker Compose failed. Check the output above.
    pause
    exit /b 1
)

rem ---------- 5. Wait for backend healthy ----------
echo Waiting for backend health check...
set /a health_wait=0
:wait_health
timeout /t 5 /nobreak >nul
set "health=starting"
for /f "usebackq tokens=*" %%s in (`docker inspect -f "{{.State.Health.Status}}" ragent-backend 2^>nul`) do set "health=%%s"
if /i "!health!"=="healthy" goto frontend_wait
set /a health_wait+=1
if !health_wait! GEQ 60 (
    echo [WARN] Backend is not healthy after 5 minutes.
    docker compose ps
    echo Use "docker compose logs -f backend" for details.
    pause
    exit /b 1
)
goto wait_health

:frontend_wait
echo Backend healthy. Waiting for frontend health check...
set /a fwait=0
:wait_frontend
timeout /t 3 /nobreak >nul
set "fhealth=starting"
for /f "usebackq tokens=*" %%s in (`docker inspect -f "{{.State.Health.Status}}" ragent-frontend 2^>nul`) do set "fhealth=%%s"
if /i "!fhealth!"=="healthy" goto startup_done
set /a fwait+=1
if !fwait! GEQ 30 (
    echo [WARN] Frontend is not healthy after 90 seconds. Trying anyway.
    goto startup_done
)
goto wait_frontend

:startup_done
echo.
echo ============================================
echo Ragent is ready.
echo Frontend: http://localhost:5177
echo Login:    admin / admin
echo ============================================
start "" "http://localhost:5177"
pause
exit /b 0
