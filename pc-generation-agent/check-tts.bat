@echo off
setlocal
cd /d "%~dp0"
if not exist ".venv-tts\Scripts\python.exe" (
  echo [ERROR] Run setup-tts.bat first.
  pause
  exit /b 1
)
".venv-tts\Scripts\python.exe" tools\tts_setup.py --check
set "RESULT=%ERRORLEVEL%"
pause
exit /b %RESULT%
