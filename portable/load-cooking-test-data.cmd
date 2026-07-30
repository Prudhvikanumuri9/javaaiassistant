@echo off
setlocal
cd /d "%~dp0"
if not exist "personal-assistant.jar" (
  echo ERROR: personal-assistant.jar was not found in %CD%
  exit /b 1
)
java.exe -jar personal-assistant.jar --seed-cooking-test-data
exit /b %errorlevel%
