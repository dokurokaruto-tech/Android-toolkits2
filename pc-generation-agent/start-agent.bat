@echo off
setlocal
cd /d "%~dp0"

if not exist config.json (
  copy /Y config.example.json config.json >nul
  echo [INFO] config.json was created. Default SD API: http://127.0.0.1:7860
  echo [INFO] Edit config.json if the SD API or output folder is different.
)

where py >nul 2>nul
if %ERRORLEVEL% EQU 0 goto use_py
where python >nul 2>nul
if %ERRORLEVEL% EQU 0 goto use_python
echo [ERROR] Python 3 was not found. Install Python 3.10 or later.
pause
exit /b 1

:use_py
set "PYTHON_CMD=py -3"
goto ensure_packages

:use_python
set "PYTHON_CMD=python"

:ensure_packages
%PYTHON_CMD% -c "import PIL" >nul 2>nul
if %ERRORLEVEL% EQU 0 goto run_agent
echo [INFO] Installing the mobile-thumbnail component...
%PYTHON_CMD% -m pip install -r requirements.txt
if not %ERRORLEVEL% EQU 0 (
  echo [ERROR] Pillow installation failed. Check the PC internet connection.
  pause
  exit /b 1
)

:run_agent
%PYTHON_CMD% agent.py --config config.json
if not %ERRORLEVEL% EQU 0 pause
endlocal
