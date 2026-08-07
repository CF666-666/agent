@echo off
setlocal EnableExtensions EnableDelayedExpansion
title Ragent Startup

cd /d "%~dp0"

echo ============================================
echo Ragent one-click startup
echo ============================================
echo.

rem Check Docker CLI and Docker daemon.
where docker >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker CLI was not found.
    echo Install Docker Desktop and run this script again.
    pause
    exit /b 1
)

docker info >nul 2>&1
if errorlevel 1 (
    echo Docker is not ready. Trying to start Docker Desktop...
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

rem Compose reads inherited system/user environment variables automatically.
rem .env is only a fallback for BAILIAN_API_KEY.
if defined BAILIAN_API_KEY (
    echo [OK] BAILIAN_API_KEY detected from the process environment.
) else if exist ".env" (
    findstr /b "BAILIAN_API_KEY=." ".env" >nul 2>&1
    if not errorlevel 1 (
        echo [OK] BAILIAN_API_KEY detected in .env.
    ) else (
        goto missing_key
    )
) else (
    goto missing_key
)

if defined SILICONFLOW_API_KEY (
    echo [OK] SILICONFLOW_API_KEY detected from the process environment.
) else if exist ".env" (
    findstr /b "SILICONFLOW_API_KEY=." ".env" >nul 2>&1
    if errorlevel 1 echo [WARN] SILICONFLOW_API_KEY is not set; embedding fallback may be used.
) else (
    echo [WARN] SILICONFLOW_API_KEY is not set; embedding fallback may be used.
)

echo.
echo Building and starting all services. The first build may take 5-15 minutes.
docker compose up -d --build
if errorlevel 1 (
    echo [ERROR] Docker Compose failed. Check the output above.
    pause
    exit /b 1
)

echo Waiting for the backend health check...
set /a health_wait=0
:wait_health
timeout /t 5 /nobreak >nul
set "health=starting"
for /f "usebackq tokens=*" %%s in (`docker inspect -f "{{.State.Health.Status}}" ragent-backend 2^>nul`) do set "health=%%s"
if /i "!health!"=="healthy" goto startup_done
set /a health_wait+=1
if !health_wait! GEQ 60 (
    echo [WARN] Backend is not healthy after 5 minutes.
    docker compose ps
    echo Use "docker compose logs -f backend" for details.
    pause
    exit /b 1
)
goto wait_health

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

:missing_key
echo [ERROR] BAILIAN_API_KEY was not found.
echo Set it as a Windows system/user environment variable, then reopen this script.
echo Or create .env next to docker-compose.yml and add BAILIAN_API_KEY=...
pause
exit /b 1
