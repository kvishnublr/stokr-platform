@echo off
REM Safe for IntelliJ "External Tool": Program = this file (or cmd /c), Working dir = project root.
set "SCRIPT_DIR=%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%kill-ports.ps1" 8080
exit /b 0
