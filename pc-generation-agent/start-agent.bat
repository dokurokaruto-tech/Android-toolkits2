@echo off
setlocal
cd /d "%~dp0"

if not exist config.json (
  copy /Y config.example.json config.json >nul
  echo [INFO] config.json was created. Default SD API: http://127.0.0.1:7860
  echo [INFO] Edit config.json if the SD API or output folder is different.
)

where py >nul 2>nul
if %ERRORLEVEL% EQU 0 (
  py -3 agent.py --config config.json
) else (
  where python >nul 2>nul
  if not %ERRORLEVEL% EQU 0 (
    echo [ERROR] Python 3 was not found. Install Python 3.10 or later.
    pause
    exit /b 1
  )
  python agent.py --config config.json
)

if not %ERRORLEVEL% EQU 0 pause
endlocal
