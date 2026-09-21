@echo off
setlocal
cd /d "%~dp0"
set "PYTHON=.venv-tts\Scripts\python.exe"
if not exist "%PYTHON%" goto failed
choice /C YN /N /M "Restore standard TTS and disable warm VRAM retention? [Y/N]: "
if errorlevel 2 exit /b 2
if not errorlevel 1 exit /b 2
"%PYTHON%" tools\tts_fast_setup.py --disable
if errorlevel 1 goto failed
echo [OK] Restart start-agent.bat. Installed packages and model files are unchanged.
pause
exit /b 0
:failed
echo [ERROR] Run setup-tts.bat or check config.local.json.
pause
exit /b 1
