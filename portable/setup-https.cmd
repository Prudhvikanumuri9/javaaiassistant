@echo off
if "%~1"=="" (
  echo Usage: setup-https.cmd YOUR_PC_IP
  echo Example: setup-https.cmd 192.168.1.25
  exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-https.ps1" -LanIp "%~1"
