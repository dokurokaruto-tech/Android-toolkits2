@echo off
setlocal
rem Interpreter detection must never let Python Install Manager download silently.
set "PYTHON_MANAGER_AUTOMATIC_INSTALL=false"
set "PYLAUNCHER_ALLOW_INSTALL="
cd /d "%~dp0"

if not exist config.json (
  copy /Y config.example.json config.json >nul
  echo [INFO] config.json was created. Default SD API: http://127.0.0.1:7860
  echo [INFO] Use config.local.json for machine-specific settings and API keys.
)

if exist ".venv-tts\Scripts\python.exe" (
  set "PYTHON_CMD=.venv-tts\Scripts\python.exe"
  goto ensure_packages
)

py -3 -c "import sys; assert sys.version_info[:2] >= (3,10)" >nul 2>nul
if not errorlevel 1 goto use_py
python -c "import sys; assert sys.version_info[:2] >= (3,10)" >nul 2>nul
if not errorlevel 1 goto use_python
echo [INFO] Python was not found. Opening interactive setup.
call setup-tts.bat
if errorlevel 1 exit /b %ERRORLEVEL%
set "PYTHON_CMD=.venv-tts\Scripts\python.exe"
goto ensure_packages

:use_py
set "PYTHON_CMD=py -3"
goto ensure_packages

:use_python
set "PYTHON_CMD=python"

:ensure_packages
%PYTHON_CMD% -c "import PIL" >nul 2>nul
if %ERRORLEVEL% EQU 0 goto run_agent
choice /C YN /N /M "Pillow is missing. Install the mobile-thumbnail library? [Y/N]: "
if errorlevel 2 goto canceled
if not errorlevel 1 goto canceled
echo [INFO] Installing the mobile-thumbnail component...
%PYTHON_CMD% -m pip install -r requirements.txt
if not %ERRORLEVEL% EQU 0 (
  echo [ERROR] Pillow installation failed. Check the PC internet connection.
  pause
  exit /b 1
)

:run_agent
%PYTHON_CMD% agent.py --config config.json
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" pause
exit /b %RESULT%

:canceled
echo [INFO] Installation canceled. The agent was not started.
pause
exit /b 2
