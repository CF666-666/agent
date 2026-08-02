@echo off
chcp 65001 >nul
title Ragent 一键停止

cd /d "%~dp0"

echo ============================================
echo    Ragent 智研中枢系统 - 一键停止
echo ============================================
echo.
echo 正在停止全部服务（数据卷保留，下次启动数据不丢失）...
docker compose down
echo.
echo 已停止。按任意键关闭本窗口。
pause >nul
