@echo off
setlocal
cd /d "%~dp0"
set "MODEL_DIR=models\vision"
if not exist "%MODEL_DIR%" mkdir "%MODEL_DIR%"

set "MODEL=%MODEL_DIR%\Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf"
set "PROJECTOR=%MODEL_DIR%\mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf"
set "BASE=https://huggingface.co/ggml-org/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main"

echo Downloading/verifying the local Qwen2.5-VL vision model...
if not exist "%MODEL%" curl.exe -L --fail --retry 3 -o "%MODEL%" "%BASE%/Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf"
if errorlevel 1 exit /b 1
if not exist "%PROJECTOR%" curl.exe -L --fail --retry 3 -o "%PROJECTOR%" "%BASE%/mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf"
if errorlevel 1 exit /b 1

certutil -hashfile "%MODEL%" SHA256 | find /i "d02fe9b69ad8cadbbd228e387667af66612c44bed29ffc8eb1e7caf9ac486c12" >nul
if errorlevel 1 (
  echo ERROR: Vision model checksum failed. Delete "%MODEL%" and run this command again.
  exit /b 1
)
certutil -hashfile "%PROJECTOR%" SHA256 | find /i "980c9b2f78c04e6cff93d277ada09e768394f112d75db3b4e9dea8a69f9fb904" >nul
if errorlevel 1 (
  echo ERROR: Vision projector checksum failed. Delete "%PROJECTOR%" and run this command again.
  exit /b 1
)
echo Vision model installation is complete.
endlocal
