@echo off
setlocal
cd /d "%~dp0"

if exist ".venv-tts\Scripts\python.exe" goto install
py -3.12 -c "import struct; assert struct.calcsize('P') == 8" >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Install 64-bit Python 3.12 with the Python launcher, then rerun.
  goto failed
)
py -3.12 -m venv .venv-tts
if errorlevel 1 goto failed

:install
set "PYTHON=.venv-tts\Scripts\python.exe"
"%PYTHON%" -c "import sys,struct; assert sys.version_info[:2] == (3,12) and struct.calcsize('P') == 8"
if errorlevel 1 goto failed
"%PYTHON%" -m pip install --upgrade pip
if errorlevel 1 goto failed
"%PYTHON%" -m pip install torch==2.7.1 torchaudio==2.7.1 --index-url https://download.pytorch.org/whl/cu126
if errorlevel 1 goto failed
"%PYTHON%" -m pip install -r requirements.txt -r requirements-tts.txt
if errorlevel 1 goto failed
if "%~1"=="" goto prepare_default
"%PYTHON%" tools\tts_setup.py --prepare --model-dir "%~1"
goto prepared

:prepare_default
"%PYTHON%" tools\tts_setup.py --prepare
:prepared
if errorlevel 1 goto failed
"%PYTHON%" tools\tts_setup.py --check
if errorlevel 1 (
  echo [INFO] Packages installed. Fix config.local.json or the reported errors.
  echo [INFO] Run check-tts.bat again after fixing them.
  goto failed
)
echo [OK] Ready for a real voice test. Run start-agent.bat.
pause
exit /b 0

:failed
echo [ERROR] Setup or validation did not complete. See the output above.
pause
exit /b 1
