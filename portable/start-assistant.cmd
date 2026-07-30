@echo off
setlocal
if /I "%~1"=="--background" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-assistant.ps1" -Background
  exit /b %errorlevel%
)
if /I "%~2"=="--background" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-assistant.ps1" "%~1" -Background
  exit /b %errorlevel%
)
if "%~1"=="" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-assistant.ps1"
) else (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-assistant.ps1" "%~1"
)
exit /b %errorlevel%
