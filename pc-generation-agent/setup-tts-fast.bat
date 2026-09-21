@echo off
setlocal
cd /d "%~dp0"
set "PYTHON=.venv-tts\Scripts\python.exe"
if not exist "%PYTHON%" goto base_required
"%PYTHON%" tools\tts_setup.py --packages-check
if errorlevel 1 goto base_required
echo [INFO] Stop start-agent.bat and other GPU applications first.
echo [INFO] Optional CUDA Graph backend. Uses your existing model, FP16 and SDPA.
echo [INFO] First request captures graphs. Later requests reuse them for up to 120 idle seconds.
echo [INFO] TTS releases VRAM before agent image jobs. Direct Forge UI use is not coordinated.
echo [INFO] RTX 2070 speed, voice quality and VRAM fit still require a real voice test.
call :confirm "Install/check the fast backend and enable it?"
if errorlevel 1 goto canceled
"%PYTHON%" tools\tts_fast_setup.py --check
if not errorlevel 1 goto validate
"%PYTHON%" -m pip install -r requirements-tts-fast.txt "torch==2.7.1+cu126" "torchaudio==2.7.1+cu126"
if errorlevel 1 goto failed
:validate
"%PYTHON%" -m pip check
if errorlevel 1 goto failed
"%PYTHON%" tools\tts_setup.py --runtime-check --backend cuda_graph
if errorlevel 1 goto failed
"%PYTHON%" tools\tts_fast_setup.py --enable
if errorlevel 1 goto failed
echo [OK] Restart start-agent.bat and compare two consecutive short voice requests.
echo [INFO] To revert, run setup-tts-standard.bat. No packages or model files are removed.
pause
exit /b 0

:base_required
echo [ERROR] Run setup-tts.bat first, then run this file again.
goto failed
:canceled
echo [INFO] Canceled. No installation or settings change was performed.
pause
exit /b 2
:failed
echo [ERROR] Fast setup did not complete. Earlier approved installs may remain.
echo [INFO] The backend setting is changed only after successful checks.
pause
exit /b 1
:confirm
choice /C YN /N /M "%~1 [Y/N]: "
if errorlevel 2 exit /b 1
if errorlevel 1 exit /b 0
exit /b 1
